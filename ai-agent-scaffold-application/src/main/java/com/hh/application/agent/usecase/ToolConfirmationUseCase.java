package com.hh.application.agent.usecase;

import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ToolConfirmationRecord;

/**
 * 工具确认查询与恢复用例。
 *
 * <p>确认恢复是新的 Runner 调用，不保持原 SSE/Rx 订阅；客户端应使用 confirmationId
 * 查询并重新建立同步或流式请求。</p>
 */
public interface ToolConfirmationUseCase {

    ToolConfirmationRecord get(String confirmationId, String userId);

    ChatResult resolve(
            String confirmationId,
            String agentId,
            String userId,
            boolean confirmed);

    ChatStream resolveStream(
            String confirmationId,
            String agentId,
            String userId,
            boolean confirmed);
}
