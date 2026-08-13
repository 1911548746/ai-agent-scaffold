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
    // 限制单个 LLM Agent 在一次 invocation 中执行的最大 ReAct 步数，防止工具链意外循环。
    @NotNull
    @Min(1)
    private Integer maxSteps = 8;
    // 仅保存 MCP/Local Tool 引用；Skill 使用下方独立的 skill-refs 字段。
    @NotNull
    private List<@NotBlank String> toolRefs = new ArrayList<>();
    // 当前运行时只支持一个 SkillToolset，但使用列表为后续多 Toolset 扩展保留配置形态。
    @NotNull
    private List<@NotBlank String> skillRefs = new ArrayList<>();
    // 仅用于在未知引用或校验失败时指向具体 YAML 文件。
    private transient String sourcePath;
}
