package com.hh.domain.agent.model.runtime;

import lombok.Data;

/**
 * Agent 的对外展示元数据。
 *
 * <p>该对象不包含模型、工具或 Runner 配置，可安全用于运行时列表与 HTTP DTO 映射。</p>
 */
@Data
public class AgentDescriptor {

    private String agentId;
    private String agentName;
    private String agentDesc;
}
