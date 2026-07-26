package com.hh.domain.agent.model.definition;

import lombok.Data;

/**
 * 上下文压缩策略的默认值，必须保持与 YAML 编译前的默认行为一致。
 */
@Data
public class ContextCompressionDefinition {

    // slidingWindow、fixedSize 由 ADK 生成摘要；pureSlidingWindow 仅本地裁剪历史 Event。
    private String type = "fixed-size";
    private Integer compactionInterval = 12;
    private Integer overlapSize = 2;
    private Integer tokenThreshold = 12000;
    private Integer eventRetentionSize = 30;
    // pureSlidingWindow 按完整 invocation 保留，而不是按 Event 条数截断。
    private Integer invocationRetentionSize = 12;
}
