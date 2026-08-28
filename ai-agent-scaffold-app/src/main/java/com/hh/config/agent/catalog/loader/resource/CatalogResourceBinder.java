package com.hh.config.agent.catalog.loader.resource;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
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

    /** 展开 catalog 中的精确地址或 Ant 模式，并按物理资源身份去重。 */
    public List<LocatedResource> resolve(List<String> locations) {
        // 使用 LinkedHashMap 按首次出现顺序保存资源，同时以物理身份消除重复匹配。
        Map<String, LocatedResource> resourcesByIdentity = new LinkedHashMap<>();
        for (String location : locations) {
            // classpath*: 会跨多个位置聚合资源，无法提供本编译链要求的稳定来源边界。
            if (location.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
                throw support.error(location, "classpath*: resource scanning is not supported");
            }
            // 先展开精确地址或 Ant 模式；没有匹配项立即失败，避免空配置被静默接受。
            Resource[] resources = getResources(location);
            // Spring 返回顺序可能依赖文件系统，先按物理身份排序以保证构建结果可复现。
            Arrays.sort(resources, Comparator.comparing(this::resourceIdentity));
            for (Resource resource : resources) {
                // 模式匹配结果中的资源仍需再次确认存在，防止延迟资源对象进入绑定阶段。
                if (!resource.exists()) {
                    throw support.error(location, "configuration resource does not exist");
                }
                // 精确地址使用配置原文；模式地址使用资源描述，便于区分实际匹配到的文件。
                String sourcePath = isPattern(location) ? resource.getDescription() : location;
                // 同一资源可能被多个 location 命中，只保留第一次出现的来源路径。
                resourcesByIdentity.putIfAbsent(resourceIdentity(resource), new LocatedResource(resource, sourcePath));
            }
        }
        return List.copyOf(resourcesByIdentity.values());
    }

    /** 从单个资源绑定一个前缀对象，并将底层异常转换为不含原始值的配置异常。 */
    public <T> T bind(LocatedResource source, String prefix, Class<T> type) {
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
            // 绑定成功不代表业务配置完整；继续执行字段约束校验后才允许进入模块阶段。
            support.validate(value, source.sourcePath());
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

    private String resourceIdentity(Resource resource) {
        try {
            // URI 归一化后作为去重键，消除同一资源的相对路径或别名差异。
            return resource.getURI().normalize().toString();
        } catch (IOException exception) {
            // 极少数资源无法暴露 URI 时退回 Spring 描述，至少保持确定性的比较键。
            return resource.getDescription();
        }
    }

    public record LocatedResource(Resource resource, String sourcePath) {
    }
}
