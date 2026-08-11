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

    /** 仅允许 pending 被一个请求原子领取，返回 false 表示已经被其他请求处理。 */
    boolean claimPending(String confirmationId, String userId, String appName);

    /** 将 processing 确认单推进到终态；重复回调不会覆盖已完成状态。 */
    boolean finish(String confirmationId, String userId, ToolConfirmationStatus status);

    /** 供离线 application 测试保留最小组合方式。 */
    static ToolConfirmationPort noop() {
        return new ToolConfirmationPort() {
            @Override
            public void savePending(Long conversationId, String appName, String userId, String sessionId,
                                    ToolConfirmationRequest request) {
                // 未启用归档时不阻塞对话流；生产组合根始终注入持久化实现。
            }

            @Override
            public Optional<ToolConfirmationRecord> find(String confirmationId, String userId) {
                return Optional.empty();
            }

            @Override
            public boolean claimPending(String confirmationId, String userId, String appName) {
                return false;
            }

            @Override
            public boolean finish(String confirmationId, String userId, ToolConfirmationStatus status) {
                return false;
            }
        };
    }
}
