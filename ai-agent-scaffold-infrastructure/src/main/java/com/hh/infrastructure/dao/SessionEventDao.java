package com.hh.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.dao.po.SessionEventPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionEventDao extends BaseMapper<SessionEventPO> {

    /**
     * event_id 的联合唯一键使 ADK 重试或重复回调天然幂等；upsert 不会覆盖首条事件快照。
     */
    int upsertByEventIdentity(SessionEventPO event);
}
