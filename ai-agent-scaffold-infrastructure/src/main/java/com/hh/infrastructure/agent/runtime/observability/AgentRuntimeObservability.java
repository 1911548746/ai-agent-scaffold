package com.hh.infrastructure.agent.runtime.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 统一记录 AgentRuntimePort 调用边界的指标与 trace。
 *
 * <p>{@code agent.id} 和调用模式来自有限配置集合，可以作为低基数标签；userId、sessionId、
 * prompt 和响应内容均不得进入指标或 trace，避免基数失控和敏感数据泄漏。</p>
 */
@Component
public class AgentRuntimeObservability {

    static final String INVOCATION_OBSERVATION_NAME = "ai.agent.invocation";
    static final String FIRST_RESPONSE_TIMER_NAME = "ai.agent.response.first";
    static final String RESPONSE_COUNTER_NAME = "ai.agent.responses";
    static final String SESSION_COUNTER_NAME = "ai.agent.sessions.created";

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public AgentRuntimeObservability(
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 创建一次调用观测。调用方必须通过 success、error 或 cancel 结束它。
     */
    public Invocation startInvocation(String agentId, String mode) {
        Observation observation = Observation.createNotStarted(INVOCATION_OBSERVATION_NAME, observationRegistry)
                .contextualName("agent " + mode + " invocation")
                .lowCardinalityKeyValue("agent.id", agentId)
                .lowCardinalityKeyValue("mode", mode)
                // 所有 outcome 使用相同标签集合，避免 Prometheus 拒绝同名但标签键不同的 Meter。
                .lowCardinalityKeyValue("error.type", "none")
                .start();
        return new Invocation(agentId, mode, observation, System.nanoTime());
    }

    /**
     * 会话创建成功后计数；失败调用由异常 trace 诊断，不把错误文本写入标签。
     */
    public void recordSessionCreated(String agentId) {
        Counter.builder(SESSION_COUNTER_NAME)
                .description("Number of Agent sessions created successfully")
                .tag("agent.id", agentId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 单次 Runtime 调用的生命周期句柄，终态方法均为幂等操作。
     */
    public final class Invocation {

        private final String agentId;
        private final String mode;
        private final Observation observation;
        private final long startedAtNanos;
        private final AtomicBoolean firstResponseRecorded = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Invocation(String agentId, String mode, Observation observation, long startedAtNanos) {
            this.agentId = agentId;
            this.mode = mode;
            this.observation = observation;
            this.startedAtNanos = startedAtNanos;
        }

        /**
         * 在当前 invocation 的 trace scope 中执行同步调用，并自动记录成功或异常终态。
         */
        public <T> T observe(Supplier<T> action) {
            try (Observation.Scope ignored = observation.openScope()) {
                T result = action.get();
                success();
                return result;
            } catch (RuntimeException | Error error) {
                error(error);
                throw error;
            }
        }

        /**
         * RxJava 回调可能运行在其他线程；显式打开 scope 才能让回调日志和下游操作关联同一 trace。
         */
        public void inScope(Runnable action) {
            try (Observation.Scope ignored = observation.openScope()) {
                action.run();
            }
        }

        /**
         * 记录可交付给客户端的响应片段。当前边界拿不到可靠 token usage，因此不伪造 token 指标。
         */
        public void recordResponse() {
            if (finished.get()) {
                return;
            }
            Counter.builder(RESPONSE_COUNTER_NAME)
                    .description("Number of displayable Agent response events")
                    .tags("agent.id", agentId, "mode", mode)
                    .register(meterRegistry)
                    .increment();

            if (firstResponseRecorded.compareAndSet(false, true)) {
                Timer.builder(FIRST_RESPONSE_TIMER_NAME)
                        .description("Time from invocation start to the first displayable response event")
                        .tags("agent.id", agentId, "mode", mode)
                        .register(meterRegistry)
                        .record(Duration.ofNanos(System.nanoTime() - startedAtNanos));
            }
        }

        public boolean success() {
            return finish("success", null);
        }

        public boolean error(Throwable error) {
            return finish("error", error);
        }

        public boolean cancel() {
            return finish("cancelled", null);
        }

        private boolean finish(String outcome, Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            observation.lowCardinalityKeyValue("outcome", outcome);
            if (error != null) {
                observation.lowCardinalityKeyValue("error.type", error.getClass().getSimpleName());
                // 原异常仍由业务日志记录；trace 只接收固定消息，避免供应商响应或凭据进入 exporter。
                observation.error(new SanitizedObservationException());
            }
            observation.stop();
            return true;
        }
    }

    /**
     * 只用于把 Observation 标记为失败，不携带原异常 message、cause 或堆栈。
     */
    private static final class SanitizedObservationException extends RuntimeException {

        private SanitizedObservationException() {
            super("Agent invocation failed", null, false, false);
        }
    }
}
