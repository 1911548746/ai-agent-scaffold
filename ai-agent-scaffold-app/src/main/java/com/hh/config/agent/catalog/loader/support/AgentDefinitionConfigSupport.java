package com.hh.config.agent.catalog.loader.support;

import com.hh.config.agent.catalog.AgentConfigurationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loader 节点共享的基础能力。
 *
 * <p>本类负责统一各节点共同遵守的错误格式、Bean Validation、schema 版本和秘密占位符解析规则。</p>
 */
@Component
public class AgentDefinitionConfigSupport {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final Validator validator;
    private final Environment environment;

    public AgentDefinitionConfigSupport(Validator validator, Environment environment) {
        this.validator = validator;
        this.environment = environment;
    }

    /** 对 catalog 或单个 YAML 绑定对象执行统一的字段路径校验。 */
    public <T> void validate(T value, String sourcePath) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (violations.isEmpty()) {
            return;
        }
        String details = violations.stream()
                .sorted(Comparator.comparing(left -> left.getPropertyPath().toString()))
                .map(violation -> sourcePath + "." + violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        throw new AgentConfigurationException(details);
    }

    /**
     * 解析模型、MCP 和 stdio 配置中的环境占位符。
     *
     * <p>异常只暴露 YAML 字段路径，不暴露占位符值、API key、header 或环境变量内容。</p>
     */
    public String resolveRequired(String value, String path) {
        try {
            String resolved = environment.resolveRequiredPlaceholders(value);
            if (!hasText(resolved)) {
                throw error(path, "resolved value must not be blank");
            }
            return resolved;
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(path + ": required environment placeholder is not configured");
        }
    }

    /** 当前编译器只理解一个 schema 版本，避免新结构被静默按旧规则解释。 */
    public void requireSchemaVersion(Integer version, String path) {
        if (version == null || version != SUPPORTED_SCHEMA_VERSION) {
            throw error(path, "unsupported schema-version; expected " + SUPPORTED_SCHEMA_VERSION);
        }
    }

    public boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public AgentConfigurationException error(String path, String message) {
        return new AgentConfigurationException(path + ": " + message);
    }
}
