package com.hh.infrastructure.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AttachmentObjectDao extends BaseMapper<AttachmentObjectPO> {

    AttachmentObjectPO selectActiveByArtifactName(
            @Param("storageType") String storageType,
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("artifactVersion") Integer artifactVersion);

    List<String> selectActiveArtifactNames(
            @Param("storageType") String storageType,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<AttachmentObjectPO> selectByArtifactName(
            @Param("storageType") String storageType,
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<Integer> selectArtifactVersions(
            @Param("storageType") String storageType,
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    int deleteByArtifactName(
            @Param("storageType") String storageType,
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<AttachmentObjectPO> selectBySession(
            @Param("storageType") String storageType,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<AttachmentObjectPO> selectExpired(
            @Param("storageType") String storageType,
            @Param("appName") String appName,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
