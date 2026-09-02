package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupPlan;

import java.util.List;

/**
 * 为不同 ADK 上下文压缩模式规划安全的 Event 清理集合。
 *
 * <p>每个 Runtime 压缩策略直接组合对应的实现，避免由字符串注册表隐式选择删除语义。</p>
 */
public interface CompactionCleanupPolicy {

    /**
     * 根据 ADK 本轮实际提交给摘要器的 Event 及保留参数计算可删除 ID。
     *
     * <p>返回的 ID 只是“摘要成功后允许删除”的候选集合，不代表此处立即删除；返回空计划时，
     * 调用方必须保守保留，不能根据时间戳或数据库行号自行推断删除范围。</p>
     */
    CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context);
}
