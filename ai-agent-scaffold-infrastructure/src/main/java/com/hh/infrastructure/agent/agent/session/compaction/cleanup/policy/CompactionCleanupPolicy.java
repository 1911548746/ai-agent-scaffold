package com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupPlan;

import java.util.List;

/**
 * 为不同 ADK 上下文压缩模式规划安全的 Event 清理集合。
 *
 * <p>这是扩展插槽：新增压缩方式时，实现本接口并声明 {@link #type()} 即可被
 * {@link CompactionCleanupPolicyRegistry} 自动发现，无需修改存储层。</p>
 */
public interface CompactionCleanupPolicy {

    /** 对应 {@code context-compress.type} 的稳定配置值。 */
    String type();

    /**
     * 根据 ADK 本轮实际提交给 summarizer 的 Event 及保留参数计算可删除 ID。
     *
     * <p>返回空计划表示保守保留，调用方不得根据时间戳或数据库行号自行推断删除范围。</p>
     */
    CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context);
}
