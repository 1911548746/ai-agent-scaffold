package com.hh.domain.agent.model.definition;

import com.hh.domain.agent.model.enums.ArtifactStorageType;
import lombok.Data;

import java.time.Duration;

/**
 * 单个 Agent Runtime 的 Artifact 存储策略。
 *
 * <p>这里只保存经过 Loader 校验的后端选择和保留语义；MinIO 凭据、本地根目录等部署参数
 * 留在 app/infrastructure 边界，避免领域定义携带基础设施连接信息。</p>
 */
@Data
public class ArtifactStorageDefinition {

    private ArtifactStorageType type = ArtifactStorageType.LOCAL;
    private Duration ttl;
    private boolean lifecycleEnabled;
}
