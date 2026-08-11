package com.hh.application.agent.port;

import com.hh.application.agent.model.ToolConfirmationRequest;

/**
 * 保存待人工确认工具请求的 application 出站端口。
 */
public interface ToolConfirmationPort {

    void savePending(
            Long conversationId,
            String appName,
            String userId,
            String sessionId,
            ToolConfirmationRequest request);

    /** 供离线 application 测试保留最小组合方式。 */
    static ToolConfirmationPort noop() {
        return (conversationId, appName, userId, sessionId, request) -> {
            // 未启用归档时不阻塞对话流；生产组合根始终注入持久化实现。
        };
    }
}
