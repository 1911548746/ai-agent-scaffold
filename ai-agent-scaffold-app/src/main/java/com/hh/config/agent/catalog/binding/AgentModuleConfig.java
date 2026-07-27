package com.hh.config.agent.catalog.binding;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 agents/*.yml 文件的绑定模型，描述单个可由 ADK 创建的 LLM Agent。
 */
@Data
@Validated
public class AgentModuleConfig {

    @NotNull
    @Min(1)
    private Integer schemaVersion;
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    @NotBlank
    private String description;
    @NotBlank
    private String instruction;
    private String outputKey;
    @NotBlank
    private String modelRef;
    // 保持 YAML 声明顺序，编译后同样按该顺序向 Agent 注入 MCP 与 Skill 工具。
    @NotNull
    private List<@NotBlank String> toolRefs = new ArrayList<>();
    // 仅用于在未知引用或校验失败时指向具体 YAML 文件。
    private transient String sourcePath;
}
