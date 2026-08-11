package com.hh.infrastructure.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.agent.dao.po.ToolConfirmationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ToolConfirmationDao extends BaseMapper<ToolConfirmationPO> {

    int upsertPending(ToolConfirmationPO confirmation);

    ToolConfirmationPO selectByIdentity(
            @Param("confirmationId") String confirmationId,
            @Param("userId") String userId);

    int claimPending(
            @Param("confirmationId") String confirmationId,
            @Param("userId") String userId,
            @Param("appName") String appName,
            @Param("processingStatus") String processingStatus);

    int finish(
            @Param("confirmationId") String confirmationId,
            @Param("userId") String userId,
            @Param("processingStatus") String processingStatus,
            @Param("status") String status);
}
