package com.hh.application.agent.port;

import com.hh.application.agent.model.RuntimePublication;
import com.hh.application.agent.model.RuntimeReloadTrigger;
import com.hh.application.agent.model.RuntimeStatus;

import java.time.Duration;
import java.util.List;

/** 保存和查询 Runtime 发布历史的应用端口。 */
public interface RuntimePublicationHistoryPort {

    /** 记录一次实际发布尝试；调用方应传入已经脱敏的失败摘要。 */
    void record(
            RuntimeReloadTrigger trigger,
            String configDigest,
            RuntimeStatus status,
            Duration duration,
            String outcome,
            String failureSummary);

    /** 返回按时间倒序排列的不可变历史。 */
    List<RuntimePublication> recent();
}
