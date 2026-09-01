package com.hh.domain.agent.model.definition;

import lombok.Data;

import java.util.List;

/**
 * Runner 运行策略定义，不承载 ADK Runner 实例。
 */
@Data
public class RunnerDefinition {

    private String agentName;
    private Boolean persistence;
    /**
     * 是否将同一轮中的 FunctionCall 调整到 FunctionResponse 之前再交给模型。
     *
     * <p>仅在 true 时映射 ADK 1.7.0 的特定端点兼容覆盖；false 时保留 ADK 自动判定。
     * 它只影响模型历史组织，不改变工具执行顺序。</p>
     */
    private Boolean groupFunctionResponsesInHistory = false;
    private ArtifactStorageDefinition artifactStorage = new ArtifactStorageDefinition();
    private ContextCompressionDefinition contextCompress;
    private List<String> pluginNameList;
}
