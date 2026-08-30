package com.hh.infrastructure.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttachmentObjectDao extends BaseMapper<AttachmentObjectPO> {

    AttachmentObjectPO selectActiveByArtifactName(
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("artifactVersion") Integer artifactVersion);

    List<String> selectActiveArtifactNames(
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<AttachmentObjectPO> selectByArtifactName(
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    List<Integer> selectArtifactVersions(
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    int deleteByArtifactName(
            @Param("artifactName") String artifactName,
            @Param("appName") String appName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);
}
