package com.hh.infrastructure.agent.agent.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;

import java.util.List;

/**
 * ADK 会话与事件持久化扩展点。
 *
 * <p>BaseSessionService 是 Google ADK 的扩展点，因此接口必须位于 infrastructure；MySQL
 * 等具体存储实现只在此处适配，不能将 SDK 类型重新传回 domain。</p>
 */
public interface AdkSessionStore extends BaseSessionService {

    /**
     * 丢弃失败或取消 invocation 已写入的上下文 Event。
     *
     * <p>实现必须先完成持久化删除，再修改传入 Session 的内存列表，避免删除失败时主动制造两份状态。</p>
     */
    Completable discardInvocationEvents(Session session, List<String> invocationIds);

    /**
     * 保留已产生工具进度的 invocation，并用恢复 Event 补齐中断轨迹。
     *
     * <p>实现必须在同一短事务中写入恢复 Event、删除不完整 Event，提交后再同步内存列表。</p>
     */
    Completable preserveInterruptedInvocation(
            Session session,
            List<String> deletableEventIds,
            List<Event> recoveryEvents);
}
