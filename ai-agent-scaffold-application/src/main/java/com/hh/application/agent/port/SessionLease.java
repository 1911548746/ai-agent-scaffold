package com.hh.application.agent.port;

/**
 * Session 独占操作的资源句柄。
 *
 * <p>application 只负责在操作结束后释放租约，不感知基础设施层使用的 Semaphore、数据库锁或
 * 其他并发协调实现。</p>
 */
@FunctionalInterface
public interface SessionLease {

    /** 释放当前 Session 的独占操作权。 */
    void release();
}
