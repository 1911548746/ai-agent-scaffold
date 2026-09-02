package com.hh.infrastructure.agent.agent.runtime.invocation;

import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.application.agent.port.SessionLease;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.support.SensitiveDataRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 Session 串行协调主调用与上下文维护。
 *
 * <p>fixedSize 在取得许可后、Runner 写入当前用户 Event 前同步预检；slidingWindow 和
 * pureSlidingWindow 在主回复完成后异步维护。失败或取消时，无工具进度的 invocation 会被清理；
 * 已进入工具阶段的 invocation 则保留可恢复轨迹并标记中断。</p>
 */
@Slf4j
@Component
public class SessionInvocationCoordinator {

    private final Executor executor;
    private final InvocationFailureRecovery failureRecovery;
    private final ConcurrentMap<SessionKey, Semaphore> permits = new ConcurrentHashMap<>();

    public SessionInvocationCoordinator(
            @Qualifier("contextCompactionExecutor") Executor executor,
            InvocationFailureRecovery failureRecovery) {
        this.executor = executor;
        this.failureRecovery = failureRecovery;
    }

    /**
     * 为一次 Agent 调用取得 Session 独占许可，并在必要时执行调用前压缩。
     *
     * <p>返回的租约必须由调用方在成功、失败或取消路径中结束，否则同一 Session 的后续操作会一直等待。</p>
     */
    public InvocationLease begin(
            AdkAgentRuntime runtime,
            String userId,
            String sessionId,
            Content pendingUserMessage) {
        // Runtime 中的计划已经在启动编译阶段确定，这里只负责按计划执行，不再解析配置。
        RuntimeCompactionPlan plan = runtime.compactionPlan();
        SessionKey key = new SessionKey(runtime.appName(), userId, sessionId);
        // 锁键必须包含 app、user 和 session，避免不同 Agent 或用户之间互相阻塞/串数据。
        Semaphore permit = permits.computeIfAbsent(key, ignored -> new Semaphore(1));
        permit.acquireUninterruptibly();

        try {
            // 先确认 Session 存在，再执行压缩和模型调用，避免为无效身份占用后续流程。
            Session session = loadSession(runtime, userId, sessionId);
            if (plan.preflightRequired(session, pendingUserMessage)) {
                try {
                    // fixedSize 只压缩既有历史，当前用户消息尚未进入 ADK Session。
                    plan.compact(session, runtime.runner().sessionService()).blockingAwait();
                } catch (RuntimeException error) {
                    // 仅摘要模型或压缩器执行失败才属于上下文压缩不可用。
                    throw new ContextCompactionException(error);
                }
            }
            // 预检可能追加摘要或清理旧 Event，必须在预检完成后记录本轮调用的真实起点。
            InvocationBaseline baseline = InvocationBaseline.capture(session.events());
            return new InvocationLease(key, permit, runtime, userId, sessionId, baseline);
        } catch (RuntimeException | Error error) {
            permit.release();
            throw error;
        }
    }

    /**
     * 获取与 invocation 共用的 Session 独占租约，供删除、归档等跨资源操作使用。
     *
     * <p>该方法只负责并发互斥，不读取或修改 Session；调用方完成操作后必须关闭返回的租约。</p>
     */
    public SessionLease acquireSessionOperationLease(
            AdkAgentRuntime runtime, String userId, String sessionId) {
        SessionKey key = new SessionKey(runtime.appName(), userId, sessionId);
        Semaphore permit = permits.computeIfAbsent(key, ignored -> new Semaphore(1));
        permit.acquireUninterruptibly();
        return permit::release;
    }

    /** 从当前 Runtime 的 SessionService 加载完整 Session，找不到时转换为稳定业务错误。 */
    private Session loadSession(AdkAgentRuntime runtime, String userId, String sessionId) {
        Session session = runtime.runner().sessionService()
                .getSession(runtime.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    public final class InvocationLease {

        private final SessionKey key;
        private final Semaphore permit;
        private final AdkAgentRuntime runtime;
        private final String userId;
        private final String sessionId;
        private final InvocationBaseline baseline;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private InvocationLease(
                SessionKey key,
                Semaphore permit,
                AdkAgentRuntime runtime,
                String userId,
                String sessionId,
                InvocationBaseline baseline) {
            this.key = key;
            this.permit = permit;
            this.runtime = runtime;
            this.userId = userId;
            this.sessionId = sessionId;
            this.baseline = baseline;
        }

        /** 主模型失败或客户端取消时同步整理本轮 Event，再解除当前 Session 的占用。 */
        public void fail() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            try {
                failureRecovery.recover(runtime, userId, sessionId, baseline);
            } catch (Throwable cleanupError) {
                // 补偿失败不能替换已经确定的模型错误；保留两端状态并输出脱敏诊断信息。
                logInvocationCleanupFailure(key, cleanupError);
            } finally {
                permit.release();
            }
        }

        /**
         * 主回复完成后执行策略规定的维护。后置策略在后台执行，绝不把其异常反向传播给已成功的调用。
         */
        public void success() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            // success 只结束主调用；滑动窗口维护是否执行由 Runtime 计划决定。
            RuntimeCompactionPlan plan = runtime.compactionPlan();
            if (!plan.postResponseRequired()) {
                permit.release();
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        Session session = loadSession(runtime, userId, sessionId);
                        plan.compact(session, runtime.runner().sessionService()).blockingAwait();
                    } catch (Throwable error) {
                        logPostCompactionFailure(key, error);
                    } finally {
                        permit.release();
                    }
                });
            } catch (RuntimeException executorError) {
                // 线程池拒绝任务时主回复仍然成功；保留原始 Event，后续请求可再次触发维护。
                logPostCompactionFailure(key, executorError);
                permit.release();
            }
        }

    }

    private void logInvocationCleanupFailure(SessionKey key, Throwable error) {
        log.error("失败 invocation 上下文清理异常，appName: {}，sessionId: {}，异常类型: {}，异常详情: {}",
                key.appName(), key.sessionId(), error.getClass().getName(),
                SensitiveDataRedactor.formatStackTrace(error));
    }

    private void logPostCompactionFailure(SessionKey key, Throwable error) {
        Throwable mapped = ModelInvocationFailureMapper.map(error);
        if (mapped instanceof ModelInvocationException modelError) {
            log.error("后置上下文压缩失败，appName: {}，sessionId: {}，失败类型: {}，根因类型: {}，异常链:\n{}",
                    key.appName(), key.sessionId(), modelError.failureType(), modelError.rootCauseType(),
                    SensitiveDataRedactor.formatStackTrace(error));
            return;
        }
        log.error("后置上下文压缩异常，appName: {}，sessionId: {}，异常类型: {}，异常详情: {}",
                key.appName(), key.sessionId(), error.getClass().getName(),
                SensitiveDataRedactor.formatStackTrace(error));
    }

    private record SessionKey(String appName, String userId, String sessionId) {
    }

}
