package com.hh.domain.agent.model.definition;

import lombok.Data;

/**
 * 上下文压缩策略的默认值，必须保持与 YAML 编译前的默认行为一致。
 */
@Data
public class ContextCompressionDefinition {

    // sliding-window: 滑动窗口；fixed-size: 固定阈值压缩。
    private String type = "fixed-size";
    private Integer compactionInterval = 12;
    private Integer overlapSize = 2;
    private Integer tokenThreshold = 12000;
    private Integer eventRetentionSize = 30;
}
