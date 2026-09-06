package com.hh.infrastructure.agent.agent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对当前 Runtime generation 的使用租约。
 *
 * <p>调用方必须在最后一次使用 Runner、SessionService 或 ArtifactService 后关闭租约，
 * 否则旧 generation 会持续处于 draining 状态。</p>
 */
public final class RuntimeLease implements AutoCloseable {

    private final RuntimeGeneration generation;
    private final AdkAgentRuntime runtime;
    private final AtomicBoolean released = new AtomicBoolean(false);

    RuntimeLease(RuntimeGeneration generation, AdkAgentRuntime runtime) {
        this.generation = generation;
        this.runtime = runtime;
    }

    /** 兼容直接构造 Coordinator 单元测试的旧入口；生产调用必须由 Registry 发放租约。 */
    public static RuntimeLease detached(AdkAgentRuntime runtime) {
        return new RuntimeLease(null, runtime);
    }

    /** 返回被租约保护的 Runtime。 */
    public AdkAgentRuntime runtime() {
        if (released.get()) {
            throw new IllegalStateException("runtime lease has been released");
        }
        return runtime;
    }

    /** 释放引用；重复调用不会重复减少 generation 计数。 */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            if (generation != null) {
                generation.release();
            }
        }
    }
}
