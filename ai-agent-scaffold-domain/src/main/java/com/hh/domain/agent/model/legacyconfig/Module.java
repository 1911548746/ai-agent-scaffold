package com.hh.domain.agent.model.legacyconfig;

import lombok.Data;

import java.util.List;

@Data
public class Module {

    private ChatModel chatModel;
    private List<LlmAgent> agents;
    private List<AgentWorkflow> agentWorkflows;
    private Runner runner;

    @Data
    public static class Runner {
        private String agentName;
        private Boolean persistence;
        private ContextCompress contextCompress;
        private List<String> pluginNameList;
    }

    @Data
    public static class ContextCompress {
        // sliding-window: 滑动窗口      fixed-size: 固定窗口
        private String type = "sliding-window";
        private Integer compactionInterval = 12;    // 消息压缩间隔
        private Integer overlapSize = 2;            // 窗口重叠大小
        private Integer tokenThreshold = 12000;     // token阈值
        private Integer eventRetentionSize = 30;    // 事件保留条数
    }

}
