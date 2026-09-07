package com.hh.infrastructure.agent.agent.runtime;

import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import jakarta.annotation.PreDestroy;
import com.hh.types.support.SensitiveDataRedactor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存 ADK 运行时的不可变发布快照。
 *
 * <p>候选 Runtime 在 Registry 外完成全部装配；发布时只替换一个不可变 generation。旧 generation
 * 立即停止接受新租约，待既有租约归零后异步关闭，保证切换期间不会访问已关闭的 Runner。</p>
 */
@Component
public class AdkAgentRuntimeRegistry implements RuntimeStatusPort {

    private final AtomicReference<RuntimeGeneration> current =
            new AtomicReference<>(RuntimeGeneration.empty(0));
    private final AtomicLong nextGeneration = new AtomicLong(1);
    private final AtomicReference<RuntimeStatus> status =
            new AtomicReference<>(RuntimeStatus.initial());
    private final Counter reloadSuccessCounter;
    private final Counter reloadFailureCounter;
    private final Timer compileTimer;

    @Autowired
    public AdkAgentRuntimeRegistry(MeterRegistry meterRegistry) {
        reloadSuccessCounter = Counter.builder("ai.agent.runtime.reload.success")
                .description("Number of successful Agent Runtime reloads")
                .register(meterRegistry);
        reloadFailureCounter = Counter.builder("ai.agent.runtime.reload.failure")
                .description("Number of failed Agent Runtime reloads")
                .register(meterRegistry);
        compileTimer = Timer.builder("ai.agent.runtime.compile")
                .description("Time spent loading and compiling an Agent Runtime snapshot")
                .register(meterRegistry);
        Gauge.builder("ai.agent.runtime.generation", status, value -> value.get().generation())
                .description("Current Agent Runtime generation")
                .register(meterRegistry);
        Gauge.builder("ai.agent.runtime.count", status, value -> value.get().runtimeCount())
                .description("Number of Runtime entries in the current snapshot")
                .register(meterRegistry);
    }

    /**
     * 发布一批完整运行时。重复 ID 在替换前失败，因此不会破坏当前已发布快照。
     */
    public synchronized void publish(Collection<AdkAgentRuntime> runtimes) {
        publish(runtimes, null);
    }

    /** 发布带配置摘要的完整快照；候选校验完成前不触碰当前快照。 */
    public synchronized void publish(Collection<AdkAgentRuntime> runtimes, String configDigest) {
        RuntimeGeneration candidate = new RuntimeGeneration(
                nextGeneration.get(), runtimes, configDigest);
        nextGeneration.incrementAndGet();
        RuntimeGeneration previous = current.getAndSet(candidate);
        RuntimeStatus previousStatus = status.get();
        status.set(previousStatus.published(
                candidate.number(), candidate.configDigest(), candidate.size(), Instant.now()));
        // 原子切换已经完成；旧 generation 只允许现有租约继续使用。
        previous.beginDraining();
    }

    /** 切换为空快照；当前调用继续排空，新调用将得到 Agent 不存在错误。 */
    public synchronized void clear() {
        RuntimeGeneration empty = RuntimeGeneration.empty(nextGeneration.getAndIncrement());
        RuntimeGeneration previous = current.getAndSet(empty);
        status.updateAndGet(value -> value.cleared(empty.number()));
        previous.beginDraining();
    }

    @Override
    public RuntimeStatus current() {
        return status.get();
    }

    @Override
    public synchronized void recordCompile(Duration duration) {
        Duration safeDuration = duration == null || duration.isNegative()
                ? Duration.ZERO : duration;
        compileTimer.record(safeDuration);
        status.updateAndGet(value -> value.compiled(safeDuration.toMillis()));
    }

    @Override
    public synchronized void recordReloadSuccess() {
        reloadSuccessCounter.increment();
        status.updateAndGet(RuntimeStatus::reloadSucceeded);
    }

    @Override
    public synchronized void recordReloadFailure(Duration duration, Throwable error) {
        Duration safeDuration = duration == null || duration.isNegative()
                ? Duration.ZERO : duration;
        compileTimer.record(safeDuration);
        reloadFailureCounter.increment();
        status.updateAndGet(value -> value.reloadFailed(summarize(error), safeDuration.toMillis()));
    }

    /** 只保留异常类型和脱敏后的第一行消息，避免状态端点暴露堆栈或凭据。 */
    private String summarize(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String firstLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        String summary = error.getClass().getSimpleName() + ": " + SensitiveDataRedactor.redact(firstLine);
        return summary.length() <= 512 ? summary : summary.substring(0, 512);
    }

    public Optional<RuntimeLease> acquire(String agentId) {
        return current.get().acquireByAgentId(agentId);
    }

    public Optional<RuntimeLease> acquireByAppName(String appName) {
        return current.get().acquireByAppName(appName);
    }

    /** 为需要长时间持有根 Agent 的 ADK Web 开发 Loader 获取整代租约。 */
    public List<RuntimeLease> acquireAll() {
        return current.get().acquireAll();
    }

    /** 只读展示使用；资源调用不得从此 Map 中直接取得 Runner。 */
    public Map<String, AdkAgentRuntime> snapshot() {
        return current.get().snapshot();
    }

    public int size() {
        return current.get().size();
    }

    public List<AgentDescriptor> descriptors() {
        return current.get().snapshot().values().stream()
                .map(AdkAgentRuntime::toAgentDescriptor)
                .toList();
    }

    /**
     * Spring Context 关闭时先切空快照，再按正常 generation 规则等待请求排空。
     *
     * <p>这里不直接逐个关闭 Runtime，避免绕过在途调用的 lease；关闭线程是否能在进程退出前
     * 完成由宿主的优雅停机窗口决定。</p>
     */
    @PreDestroy
    void shutdown() {
        clear();
    }
}
