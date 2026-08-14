package com.hh.config.agent.catalog.binding;

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
 * <p>MCP Tool 必须在三种 transport 中选择一种；Local Tool 使用顶层 Bean 方法配置。Skill 使用独立的 {@link SkillModuleConfig}
 * 配置模型，避免把两类能力混在同一个资源目录和绑定对象中。</p>
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
    /** Local Tool 使用 Spring Bean 方法，不再伪装成 MCP transport。 */
    private String beanName;
    private String methodName;
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

}
