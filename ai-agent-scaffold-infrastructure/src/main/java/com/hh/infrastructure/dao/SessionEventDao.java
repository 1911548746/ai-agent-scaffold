package com.hh.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.dao.po.SessionEventPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionEventDao extends BaseMapper<SessionEventPO> {
}
