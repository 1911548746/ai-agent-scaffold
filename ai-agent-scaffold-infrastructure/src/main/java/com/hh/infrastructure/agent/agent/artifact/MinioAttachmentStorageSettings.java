package com.hh.infrastructure.agent.agent.artifact;

import java.time.Duration;

/** app 层配置转换后的不可变附件存储参数，不包含可向上层传播的 SDK 类型。 */
public record MinioAttachmentStorageSettings(
        String bucket,
        Duration ttl) {
}
