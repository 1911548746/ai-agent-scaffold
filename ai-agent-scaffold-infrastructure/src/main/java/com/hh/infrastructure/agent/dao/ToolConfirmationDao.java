package com.hh.infrastructure.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.agent.dao.po.ToolConfirmationPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ToolConfirmationDao extends BaseMapper<ToolConfirmationPO> {

    int upsertPending(ToolConfirmationPO confirmation);
}
