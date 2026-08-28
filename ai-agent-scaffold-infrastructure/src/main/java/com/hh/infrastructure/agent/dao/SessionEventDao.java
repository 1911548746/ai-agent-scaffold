package com.hh.infrastructure.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.agent.dao.param.SessionEventCompactionCleanup;
import com.hh.infrastructure.agent.dao.param.SessionEventIdCleanup;
import com.hh.infrastructure.agent.dao.po.SessionEventPO;
import com.hh.infrastructure.agent.dao.param.SessionEventQuery;
import com.hh.infrastructure.agent.dao.param.SessionInvocationEventCleanup;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SessionEventDao extends BaseMapper<SessionEventPO> {

    /**
     * event_id 的联合唯一键使 ADK 重试或重复回调天然幂等；upsert 不会覆盖首条事件快照。
     */
    int upsertByEventIdentity(SessionEventPO event);

    /**
     * 读取当前仍保存在 Session 中的全部 Event。
     *
     * <p>不能以最新 compaction Event 作为行 ID 下界：sliding-window overlap 的原始 Event 位于摘要之前，
     * 仍必须交给 ADK 参与下一轮压缩。</p>
     */
    List<SessionEventPO> selectSessionEvents(SessionEventQuery query);

    /**
     * ADK 显式要求最近 N 条事件时，直接由数据库完成截断并按时间正序返回。
     */
    List<SessionEventPO> selectRecentEvents(SessionEventQuery query);

    /**
     * ADK 显式指定时间边界时，由数据库过滤，避免无效 JSON 反序列化。
     */
    List<SessionEventPO> selectEventsAfterTimestamp(SessionEventQuery query);

    /**
     * 删除已被当前摘要精确覆盖的源 Event；摘要自身永远不在删除集合内。
     */
    int deleteCompactedEvents(SessionEventCompactionCleanup cleanup);

    /**
     * 删除一次失败或取消 invocation 产生的全部 Event，防止孤立用户消息继续参与上下文。
     */
    int deleteInvocationEvents(SessionInvocationEventCleanup cleanup);

    /**
     * 删除中断 invocation 中的 partial 和纯中间文本 Event，保留用户与工具轨迹。
     */
    int deleteEventsByIds(SessionEventIdCleanup cleanup);
}
