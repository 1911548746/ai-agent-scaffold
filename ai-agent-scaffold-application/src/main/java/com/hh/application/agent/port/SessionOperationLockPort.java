package com.hh.application.agent.port;

/**
 * Session 操作并发协调端口。
 *
 * <p>删除 Conversation/Session 等跨资源操作需要通过该端口与在途 Agent invocation 串行化，
 * 但不应因此依赖 AgentRuntimePort 的模型调用契约。</p>
 */
public interface SessionOperationLockPort {

    /** 获取指定 Agent 用户 Session 的独占操作租约。 */
    SessionLease acquire(String agentId, String userId, String sessionId);
}
