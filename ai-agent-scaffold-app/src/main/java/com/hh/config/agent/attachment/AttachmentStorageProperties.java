package com.hh.config.agent.attachment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** MinIO 附件存储的部署配置；访问凭据仅允许从环境变量注入。 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.attachment", ignoreUnknownFields = false)
public class AttachmentStorageProperties {

    private boolean enabled = false;

    @NotBlank
    private String endpoint;
    @NotBlank
    private String bucket;
    @NotBlank
    private String accessKey;
    @NotBlank
    private String secretKey;
    @NotNull
    private Duration ttl = Duration.ofHours(24);
}
