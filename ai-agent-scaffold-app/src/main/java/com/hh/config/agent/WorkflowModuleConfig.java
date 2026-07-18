package com.hh.config.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 workflows/*.yml 文件的绑定模型。
 *
 * <p>子节点先使用稳定 ID 表达，Loader 完成校验和拓扑排序后才转换为 ADK 所需的运行时名称。</p>
 */
@Data
@Validated
public class WorkflowModuleConfig {

    @NotNull
    @Min(1)
    private Integer schemaVersion;
    @NotBlank
    private String id;
    @NotBlank
    private String type;
    @NotBlank
    private String name;
    @NotBlank
    private String description;
    private String instruction;
    // 直接包含的 LLM Agent；与子工作流分开保存，便于分别做引用校验和循环检测。
    @NotNull
    private List<@NotBlank String> subAgentRefs = new ArrayList<>();
    @NotNull
    private List<@NotBlank String> subWorkflowRefs = new ArrayList<>();
    @Min(1)
    private Integer maxIterations = 3;
    // 仅用于错误消息，不参与最终 ADK 配置。
    private transient String sourcePath;
}
