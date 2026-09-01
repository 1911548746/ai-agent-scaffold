package com.hh.infrastructure.agent.agent.artifact;

import com.google.adk.artifacts.BaseArtifactService;
import com.hh.application.agent.port.AttachmentStoragePort;

/**
 * Runtime 内部统一的 Artifact 能力。
 *
 * <p>同一实例同时交给 ADK Runner 和 application 上传路由，防止内存后端出现“上传实例”与
 * “模型读取实例”不一致。该组合契约只存在于 infrastructure，不向上层暴露 ADK 类型。</p>
 */
public interface ArtifactStorageService extends BaseArtifactService, AttachmentStoragePort {

    String appName();
}
