package com.hh.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.dao.po.SessionPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionDao extends BaseMapper<SessionPO> {

    /**
     * 以 sessions 的联合唯一键保证同一 app、用户和 session 只会创建一条记录。
     */
    int upsertByIdentity(SessionPO session);

    /**
     * 按会话身份更新快照，避免 appendEvent 为获取主键额外查询一次 sessions 表。
     */
    int updateStateByIdentity(SessionPO session);
}
