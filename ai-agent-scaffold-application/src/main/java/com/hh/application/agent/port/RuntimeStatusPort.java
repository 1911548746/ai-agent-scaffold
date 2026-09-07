package com.hh.application.agent.port;

import com.hh.application.agent.model.RuntimeStatus;

import java.time.Duration;

/**
 * 查询和记录 Agent Runtime 发布状态的应用端口。
 *
 * <p>端口只传递不可变运维元数据；具体的 Registry、Micrometer 和异常脱敏逻辑由基础设施实现。</p>
 */
public interface RuntimeStatusPort {

    /** 返回当前已发布快照的脱敏状态。 */
    RuntimeStatus current();

    /** 记录一次编译耗时；启动编译和 Reload 编译都应调用。 */
    void recordCompile(Duration duration);

    /** 记录一次成功的手动 Reload。 */
    void recordReloadSuccess();

    /** 记录一次失败的手动 Reload，同时保留旧 Runtime。 */
    void recordReloadFailure(Duration duration, Throwable error);
}
