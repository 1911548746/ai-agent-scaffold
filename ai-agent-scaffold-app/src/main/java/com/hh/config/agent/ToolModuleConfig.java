package com.hh.config.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 tools/*.yml 文件的绑定模型。
 *
 * <p>type 为 mcp 时必须在四种 transport 中选择一种；type 为 skill 时只能声明本地 Skill 目录。
 * 具体互斥规则由 AgentDefinitionLoader 统一校验，避免依赖 YAML 字段出现顺序。</p>
 */
@Data
@Validated
public class ToolModuleConfig {

    @NotNull
    @Min(1)
    private Integer schemaVersion;
    @NotBlank
    private String id;
    @NotBlank
    private String type;
    @Valid
    private McpDefinition mcp;
    @Valid
    private SkillDefinition skill;
    // 只用于错误定位，绝不作为运行时工具参数传递。
    private transient String sourcePath;

    /**
     * MCP 支持的传输方式。这里允许绑定多个字段，随后由编译器以“恰好一个”为约束 fail-fast。
     */
    @Data
    public static class McpDefinition {
        @Valid
        private SseTransport sse;
        @Valid
        private HttpTransport http;
        @Valid
        private StdioTransport stdio;
        @Valid
        private LocalTransport local;
    }

    @Data
    public static class SseTransport {
        @NotBlank
        private String name;
        @NotBlank
        private String url;
        @NotBlank
        private String sseEndpoint;
        @Min(1)
        private Long timeout = 90L;
        @Min(1)
        private Long requestTimeout = 120L;
    }

    @Data
    public static class HttpTransport {
        @NotBlank
        private String name;
        @NotBlank
        private String url;
        // 空 Map 代表未配置 header，避免后续组装时出现 null 分支。
        @NotNull
        private Map<String, String> headers = new LinkedHashMap<>();
        @Min(1)
        private Long timeout = 90L;
        @Min(1)
        private Long requestTimeout = 120L;
    }

    @Data
    public static class StdioTransport {
        @NotBlank
        private String name;
        @NotBlank
        private String command;
        // 空集合/Map 明确表示没有额外参数或环境变量。
        @NotNull
        private List<String> args = new ArrayList<>();
        @NotNull
        private Map<String, String> env = new LinkedHashMap<>();
    }

    @Data
    public static class LocalTransport {
        @NotBlank
        private String beanName;
        @NotBlank
        private String methodName;
    }

    /**
     * Skill 不走 MCP 客户端，而是交给现有 SkillSourceFactory 从该目录加载技能描述。
     */
    @Data
    public static class SkillDefinition {
        @NotBlank
        private String directory;
    }
}
