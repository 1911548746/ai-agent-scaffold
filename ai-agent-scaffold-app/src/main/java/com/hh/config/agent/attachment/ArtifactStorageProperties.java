package com.hh.config.agent.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/** Artifact 后端的部署参数；具体后端被 Application 选中时才执行完整校验。 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.artifact-storage", ignoreUnknownFields = false)
public class ArtifactStorageProperties {

    private Minio minio = new Minio();
    private Local local = new Local();

    @Data
    public static class Minio {
        private String endpoint = "";
        private String bucket = "agent-attachments";
        private String accessKey = "";
        private String secretKey = "";
        private Duration cleanupInterval = Duration.ofHours(1);
    }

    @Data
    public static class Local {
        private Path rootDirectory = Path.of("./data/artifacts");
    }
}
