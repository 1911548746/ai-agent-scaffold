package com.hh.config.agent.catalog.loader;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.binding.SkillModuleConfig;
import com.hh.config.agent.catalog.loader.resource.CatalogResourceBinder;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置快照摘要计算器。
 *
 * <p>本类只负责收集本次加载实际消费的资源并计算 {@code configDigest}，不参与 Agent
 * Definition 的绑定、引用解析或运行时装配。资源集合只保存规范化路径和 Spring Resource，
 * 文件内容在排序后逐个读取并直接更新摘要，避免多个配置文件和 Skill 文件同时驻留内存。</p>
 */
@Component
public class ConfigSnapshotDigest {

    private static final int BUFFER_SIZE = 8192;

    private final CatalogResourceBinder resourceBinder;
    private final AgentDefinitionConfigSupport support;

    public ConfigSnapshotDigest(
            CatalogResourceBinder resourceBinder,
            AgentDefinitionConfigSupport support) {
        this.resourceBinder = resourceBinder;
        this.support = support;
    }

    /**
     * 计算一次完整 catalog 快照的 SHA-256 摘要。
     *
     * <p>catalogLocation 为空时无法确定实际资源快照，兼容旧的“仅使用已绑定 catalog”入口，
     * 此时返回 {@code null}。正式启动和 Reload 都会传入实际入口并得到稳定摘要。</p>
     */
    public String calculate(
            AgentCatalogProperties catalog,
            String catalogLocation,
            CatalogResourceBinder.LocatedResource catalogResource) {
        if (catalogLocation == null || catalogLocation.isBlank()) {
            return null;
        }

        // 先收集资源引用并按物理身份去重；这里不读取文件内容，避免产生大块临时字节数组。
        Map<String, DigestResource> resources = new LinkedHashMap<>();
        if (catalogResource != null) {
            addResource(resources, catalogResource);
        } else {
            for (CatalogResourceBinder.LocatedResource resource : resourceBinder.resolve(List.of(catalogLocation))) {
                addResource(resources, resource);
            }
        }
        addResources(resources, catalog.getModelResources(), catalogLocation);
        addResources(resources, catalog.getToolResources(), catalogLocation);
        addResources(resources, catalog.getSkillResources(), catalogLocation);
        addSkillResources(resources, catalog, catalogLocation);
        addResources(resources, catalog.getAgentResources(), catalogLocation);
        addResources(resources, catalog.getWorkflowResources(), catalogLocation);
        addResources(resources, catalog.getApplicationResources(), catalogLocation);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<DigestResource> ordered = new ArrayList<>(resources.values());
            // 资源发现顺序可能受文件系统影响，摘要必须只由路径和内容决定。
            ordered.sort(Comparator.comparing(DigestResource::path));
            for (DigestResource resource : ordered) {
                updateResourceDigest(digest, resource);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (DigestReadException exception) {
            throw new AgentConfigurationException("unable to read catalog resources for digest");
        }
    }

    /** 收集一组 catalog 模块资源；具体匹配和路径安全校验由资源 Binder 统一处理。 */
    private void addResources(
            Map<String, DigestResource> resources,
            List<String> locations,
            String catalogLocation) {
        if (locations == null) {
            return;
        }
        for (CatalogResourceBinder.LocatedResource resource : resourceBinder.resolve(locations, catalogLocation)) {
            addResource(resources, resource);
        }
    }

    /**
     * Skill YAML 只描述目录入口；SKILL.md 和 references 等实际输入也必须纳入摘要。
     * 目录解析沿用 Loader 的资源边界，避免摘要器自行扫描配置根目录之外的文件。
     */
    private void addSkillResources(
            Map<String, DigestResource> resources,
            AgentCatalogProperties catalog,
            String catalogLocation) {
        if (catalog.getSkillResources() == null) {
            return;
        }
        for (CatalogResourceBinder.LocatedResource resource
                : resourceBinder.resolve(catalog.getSkillResources(), catalogLocation)) {
            SkillModuleConfig skill = resourceBinder.bind(resource, "ai.agent.skill", SkillModuleConfig.class);
            String directory = support.resolveRequired(
                    skill.getDirectory(), resource.sourcePath() + ".directory");
            for (CatalogResourceBinder.LocatedResource file
                    : resourceBinder.resolveDirectory(directory, catalogLocation)) {
                addResource(resources, file);
            }
        }
    }

    /** 只保留资源身份、规范化路径和可重新打开的 Resource，不在集合中缓存内容。 */
    private void addResource(
            Map<String, DigestResource> resources,
            CatalogResourceBinder.LocatedResource resource) {
        String identity = resourceBinder.identity(resource);
        resources.putIfAbsent(identity, new DigestResource(normalizePath(identity), resource.resource()));
    }

    /**
     * 按既有的“路径长度、路径、内容长度、内容”协议更新摘要。
     *
     * <p>内容长度先通过流式计数获得，再重新打开 Resource 写入内容，因此不会为了长度前缀
     * 把完整文件读入内存。Spring catalog 资源支持重复打开；若底层资源无法读取则统一转为
     * 配置错误。</p>
     */
    private void updateResourceDigest(MessageDigest digest, DigestResource resource) {
        try {
            byte[] path = resource.path().getBytes(StandardCharsets.UTF_8);
            digest.update(intBytes(path.length));
            digest.update(path);

            long contentLength = countBytes(resource.resource());
            if (contentLength > Integer.MAX_VALUE) {
                throw new IOException("resource is too large to digest");
            }
            digest.update(intBytes((int) contentLength));
            try (InputStream input = resource.resource().getInputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            throw new DigestReadException(exception);
        }
    }

    /** 统计资源长度时只使用固定大小缓冲区，确保大文件不会转成单个 byte[]。 */
    private long countBytes(org.springframework.core.io.Resource resource) throws IOException {
        long length = 0;
        try (InputStream input = resource.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                length += read;
                if (length > Integer.MAX_VALUE) {
                    return length;
                }
            }
        }
        return length;
    }

    /** 统一路径分隔符，确保 Windows 和 Unix 上的摘要路径表示一致。 */
    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    /** 用固定四字节长度前缀区分相邻路径或文件内容，保持既有摘要协议。 */
    private byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    /** 输出不含配置内容的低位十六进制摘要，便于状态接口稳定展示。 */
    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record DigestResource(String path, Resource resource) {
    }

    private static final class DigestReadException extends RuntimeException {
        private DigestReadException(IOException cause) {
            super(cause);
        }
    }
}
