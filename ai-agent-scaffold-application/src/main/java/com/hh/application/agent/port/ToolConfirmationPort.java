package com.hh.application.agent.port;

import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.model.ToolConfirmationRecord;
import com.hh.domain.agent.model.enums.ToolConfirmationStatus;

import java.util.Optional;

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

    Optional<ToolConfirmationRecord> find(String confirmationId, String userId);

    /**
     * 仅允许指定版本的 pending 确认单被一个请求领取。
     *
     * <p>版本不匹配表示其他请求已经推进状态，调用方不得再次恢复同一工具调用。</p>
     */
    boolean claimPending(String confirmationId, String userId, String appName, long expectedVersion);

    /** 将 processing 确认单推进到终态；重复回调不会覆盖已完成状态。 */
    boolean finish(
            String confirmationId,
            String userId,
            ToolConfirmationStatus status,
            long expectedVersion);

}
