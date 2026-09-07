package com.hh.config.agent.catalog.loader.resource;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.boot.context.properties.source.ConfigurationPropertySources.from;

/**
 * catalog YAML 的受控资源访问入口。
 *
 * <p>它只处理资源定位、稳定排序、独立 Binder 和 Bean Validation 委托，不解释任何 model、tool、
 * agent 或 workflow 的业务字段。每份 YAML 使用独立 PropertySources，防止一个资源的字段污染另一个资源。</p>
 */
@Component
public class CatalogResourceBinder {

    private final ResourcePatternResolver resourcePatternResolver;
    private final AgentDefinitionConfigSupport support;
    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    public CatalogResourceBinder(
            ResourcePatternResolver resourcePatternResolver,
            AgentDefinitionConfigSupport support) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.support = support;
    }

    /**
     * 展开 catalog 中的精确地址或 Ant 模式，并按物理资源身份去重。
     *
     * <p>该重载保留现有绝对资源地址的行为；没有 catalog 基准目录时，不解析相对地址。</p>
     */
    public List<LocatedResource> resolve(List<String> locations) {
        return resolve(locations, null);
    }

    /**
     * 按 catalog 所在位置解析模块资源，并按物理资源身份去重。
     *
     * <p>外部 catalog 可以使用 {@code ../models/**&#47;*.yml} 这类相对路径；classpath 和 file
     * 绝对地址仍按原样处理。file 路径归一化后必须留在外部配置根目录内，避免配置通过
     * {@code ..} 读取任意外部文件。</p>
     */
    public List<LocatedResource> resolve(List<String> locations, String baseLocation) {
        // 使用 LinkedHashMap 按首次出现顺序保存资源，同时以物理身份消除重复匹配。
        Map<String, LocatedResource> resourcesByIdentity = new LinkedHashMap<>();
        for (String location : locations) {
            String resolvedLocation = resolveRelativeLocation(location, baseLocation);
            // classpath*: 会跨多个位置聚合资源，无法提供本编译链要求的稳定来源边界。
            if (resolvedLocation.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
                throw support.error(resolvedLocation, "classpath*: resource scanning is not supported");
            }
            // 先展开精确地址或 Ant 模式；没有匹配项立即失败，避免空配置被静默接受。
            Resource[] resources = getResources(resolvedLocation);
            // Spring 返回顺序可能依赖文件系统，先按物理身份排序以保证构建结果可复现。
            Arrays.sort(resources, Comparator.comparing(this::resourceIdentity));
            for (Resource resource : resources) {
                // 模式匹配结果中的资源仍需再次确认存在，防止延迟资源对象进入绑定阶段。
                if (!resource.exists()) {
                    throw support.error(resolvedLocation, "configuration resource does not exist");
                }
                // 精确地址使用配置原文；模式地址使用资源描述，便于区分实际匹配到的文件。
                String sourcePath = isPattern(resolvedLocation) ? resource.getDescription() : resolvedLocation;
                // 同一资源可能被多个 location 命中，只保留第一次出现的来源路径。
                resourcesByIdentity.putIfAbsent(resourceIdentity(resource), new LocatedResource(resource, sourcePath));
            }
        }
        return List.copyOf(resourcesByIdentity.values());
    }

    /**
     * 展开 Skill 目录中的所有文件。
     *
     * <p>Skill YAML 只保存目录入口，ADK 实际读取的是目录下的 SKILL.md 和引用文件，
     * 因此摘要和资源校验都必须看到这些文件。通配展开仍复用同一套受控资源解析和物理去重规则。</p>
     */
    public List<LocatedResource> resolveDirectory(String location, String baseLocation) {
        String resolvedLocation = resolveRelativeLocation(location, baseLocation);
        if (resolvedLocation == null || resolvedLocation.isBlank()) {
            throw support.error(String.valueOf(location), "skill directory must not be blank");
        }
        if (resolvedLocation.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
            throw support.error(resolvedLocation, "classpath*: resource scanning is not supported");
        }
        String directory = resolvedLocation.endsWith("/")
                ? resolvedLocation.substring(0, resolvedLocation.length() - 1)
                : resolvedLocation;
        // **/* 同时覆盖目录根下文件和任意深度的引用文件，避免只摘要 SKILL.md。
        List<LocatedResource> resources = resolve(List.of(directory + "/**/*")).stream()
                .filter(this::isRegularFile)
                .toList();
        if (resources.isEmpty()) {
            throw support.error(resolvedLocation, "skill directory contains no files");
        }
        return resources;
    }

    /** classpath 的展开结果可能包含目录；目录不是 ADK Skill 的可读资源，必须排除。 */
    private boolean isRegularFile(LocatedResource resource) {
        try {
            // file: 和 exploded classpath: 可以直接判断物理文件类型。
            if (resource.resource().isFile()) {
                return resource.resource().getFile().isFile();
            }
        } catch (IOException ignored) {
            // JAR 内资源无法转换为 File；这类资源由通配解析器返回的通常就是文件，继续保留。
        }
        return true;
    }

    /** 从单个资源绑定一个前缀对象，并将底层异常转换为不含原始值的配置异常。 */
    public <T> T bind(LocatedResource source, String prefix, Class<T> type) {
        return bind(source, prefix, type, true);
    }

    /**
     * 绑定 catalog 本身，并把实际入口地址写入诊断字段。
     *
     * <p>catalog 文件中的 {@code catalog-path} 只是兼容字段，实际入口以 Loader 参数为准，
     * 这样同一份配置可以被复制到不同外部目录。绑定完成后仍执行完整 Bean Validation。</p>
     */
    public AgentCatalogProperties bindCatalog(String location) {
        return bindCatalogResource(location).catalog();
    }

    /** 绑定 catalog 并保留其物理资源，供 Loader 计算本次完整快照摘要。 */
    public LocatedCatalog bindCatalogResource(String location) {
        List<LocatedResource> resources = resolve(List.of(location));
        if (resources.size() != 1) {
            throw support.error(location, "catalog location must resolve to exactly one file");
        }
        AgentCatalogProperties catalog = bind(resources.get(0), "ai.agent.config",
                AgentCatalogProperties.class, false);
        catalog.setCatalogPath(location);
        support.validate(catalog, location);
        return new LocatedCatalog(catalog, resources.get(0));
    }

    private <T> T bind(LocatedResource source, String prefix, Class<T> type, boolean validate) {
        try {
            // 每个文件创建独立 PropertySources，确保绑定只读取当前文件的 YAML 内容。
            MutablePropertySources sources = new MutablePropertySources();
            List<PropertySource<?>> loaded = yamlLoader.load(source.sourcePath(), source.resource());
            // 按 YAML loader 的顺序加入属性源，交给 Spring Binder 处理嵌套对象和类型转换。
            loaded.forEach(sources::addLast);
            // 缺少约定前缀通常意味着文件类型错误，使用带来源的配置异常明确指出问题。
            T value = new Binder(from(sources))
                    .bind(prefix, Bindable.of(type))
                    .orElseThrow(() -> support.error(source.sourcePath(), "missing configuration prefix '" + prefix + "'"));
            // 绑定成功不代表业务配置完整；正式模块绑定仍需继续执行字段约束校验。
            if (validate) {
                support.validate(value, source.sourcePath());
            }
            return value;
        } catch (AgentConfigurationException exception) {
            // 已经脱敏且带路径的配置异常直接透传，避免丢失精确错误信息。
            throw exception;
        } catch (BindException exception) {
            // Binder 异常只保留字段名，不把原始配置值写入启动日志或异常信息。
            throw new AgentConfigurationException(source.sourcePath() + "." + exception.getName() + ": configuration binding failed");
        } catch (IOException exception) {
            // YAML 读取失败时报告资源来源，底层 IO 内容不向上层暴露。
            throw new AgentConfigurationException(source.sourcePath() + ": unable to read configuration resource");
        } catch (RuntimeException exception) {
            // 兜底转换其他绑定运行时异常，保持 Loader 对外的异常类型稳定。
            throw new AgentConfigurationException(source.sourcePath() + ": configuration binding failed for '" + prefix + "'");
        }
    }

    private Resource[] getResources(String location) {
        try {
            // ResourcePatternResolver 同时支持单文件位置和通配模式，统一在这里处理展开结果。
            Resource[] resources = resourcePatternResolver.getResources(location);
            if (resources.length == 0) {
                // 区分精确位置和模式，给出对应的缺失原因。
                throw support.error(location, isPattern(location)
                        ? "configuration resource pattern does not match any files"
                        : "configuration resource does not exist");
            }
            return resources;
        } catch (IOException exception) {
            // 模式解析失败按“无匹配”处理；精确地址则报告资源解析失败。
            if (isPattern(location)) {
                throw support.error(location, "configuration resource pattern does not match any files");
            }
            throw new AgentConfigurationException(location + ": unable to resolve configuration resource");
        }
    }

    private boolean isPattern(String location) {
        // 仅识别当前资源解析器使用的通配符，避免把普通路径误判为模式。
        return location.indexOf('*') >= 0 || location.indexOf('?') >= 0;
    }

    /**
     * 按 catalog 位置解析一个资源路径，但不主动展开通配符。
     *
     * <p>该方法用于 Skill 原始资源目录等不属于 YAML 文件发现范围的路径。返回值仍保留
     * {@code classpath:} 或 {@code file:} 前缀，使下游工厂可以选择正确的资源实现。</p>
     */
    public String resolveLocation(String location, String baseLocation) {
        return resolveRelativeLocation(location, baseLocation);
    }

    private String resolveRelativeLocation(String location, String baseLocation) {
        if (location == null || location.isBlank() || baseLocation == null || baseLocation.isBlank()
                || hasResourceScheme(location) || isAbsoluteFilePath(location)) {
            return location;
        }

        if (baseLocation.startsWith("classpath:")) {
            String basePath = baseLocation.substring("classpath:".length()).replace('\\', '/');
            Path baseFile = Paths.get(basePath).normalize();
            Path catalogDirectory = baseFile.getParent();
            if (catalogDirectory == null) {
                return "classpath:" + normalizeClasspathPath(location);
            }

            // classpath catalog 与外部 catalog 使用相同目录约定：catalog 下允许访问同级模块目录，
            // 但不能通过 ../../ 跳出约定的配置根。
            Path allowedRoot = "catalog".equalsIgnoreCase(catalogDirectory.getFileName().toString())
                    ? catalogDirectory.getParent()
                    : catalogDirectory;
            if (allowedRoot == null) {
                throw support.error(baseLocation, "classpath catalog has no configuration root");
            }
            Path resolved = catalogDirectory.resolve(location.replace('*', 'x').replace('?', 'x')).normalize();
            if (!resolved.startsWith(allowedRoot)) {
                throw support.error(location, "relative resource path escapes catalog directory");
            }
            return "classpath:" + resolved.toString().replace('\\', '/');
        }

        if (baseLocation.startsWith("file:")) {
            try {
                Path baseFile = Paths.get(new URI(baseLocation)).toAbsolutePath().normalize();
                Path catalogDirectory = baseFile.getParent();
                if (catalogDirectory == null) {
                    throw support.error(baseLocation, "file catalog has no parent directory");
                }

                // 约定目录为 <config-root>/catalog/<catalog>.yml，因此允许 ../models 等同级目录，
                // 但仍把所有相对资源限制在 config-root 内。非约定目录则以 catalog 所在目录为根。
                Path allowedRoot = "catalog".equalsIgnoreCase(catalogDirectory.getFileName().toString())
                        ? catalogDirectory.getParent()
                        : catalogDirectory;
                if (allowedRoot == null) {
                    throw support.error(baseLocation, "file catalog has no configuration root");
                }
                // Windows Path 不接受 * 和 ?，先用占位符完成纯词法边界检查，再用 URI 保留通配符。
                String validationLocation = location.replace('*', 'x').replace('?', 'x');
                Path resolved = catalogDirectory.resolve(validationLocation).normalize();
                if (!resolved.startsWith(allowedRoot)) {
                    throw support.error(location, "relative resource path escapes catalog directory");
                }
                return baseFile.toUri().resolve(location.replace('\\', '/')).toString();
            } catch (URISyntaxException | IllegalArgumentException exception) {
                if (exception instanceof AgentConfigurationException configurationException) {
                    throw configurationException;
                }
                throw support.error(baseLocation, "invalid file catalog location");
            }
        }

        throw support.error(location, "relative resource path requires a classpath or file catalog location");
    }

    private boolean hasResourceScheme(String location) {
        return location.startsWith("classpath:") || location.startsWith("classpath*:")
                || location.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private boolean isAbsoluteFilePath(String location) {
        // 通配路径由 ResourcePatternResolver 解释；这里只判断普通文件路径是否为绝对路径。
        if (isPattern(location)) {
            return false;
        }
        try {
            return Paths.get(location).isAbsolute();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String normalizeClasspathPath(String path) {
        Path normalized = Paths.get(path.replace('/', java.io.File.separatorChar)).normalize();
        String value = normalized.toString().replace('\\', '/');
        if (value.equals("..") || value.startsWith("../")) {
            throw support.error(path, "relative resource path escapes catalog directory");
        }
        return value.replaceFirst("^/+", "");
    }

    private String resourceIdentity(Resource resource) {
        try {
            // URI 归一化后作为去重键，消除同一资源的相对路径或别名差异。
            return resource.getURI().normalize().toString();
        } catch (IOException exception) {
            // 极少数资源无法暴露 URI 时退回 Spring 描述，至少保持确定性的比较键。
            return resource.getDescription();
        }
    }

    /** 返回资源的物理身份，供摘要计算去除同一文件的重复匹配。 */
    public String identity(LocatedResource resource) {
        return resourceIdentity(resource.resource());
    }

    public record LocatedResource(Resource resource, String sourcePath) {
    }

    public record LocatedCatalog(AgentCatalogProperties catalog, LocatedResource resource) {
    }
}
