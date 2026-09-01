package com.hh.config.agent.attachment;

import com.hh.infrastructure.agent.agent.artifact.config.ArtifactStorageDeploymentSettings;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageServiceFactory;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioArtifactStorageSettings;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioLifecycleManager;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import io.minio.GetBucketLifecycleArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证多 Application Lifecycle 对账和无 MinIO 时的惰性边界。 */
class AttachmentStorageConfigurationTest {

    @Test
    void reconcilesWholeDayRulesAndPreservesExternalRules() throws Exception {
        MinioClient client = mock(MinioClient.class);
        LifecycleRule external = rule("external-rule", "external/", 30);
        LifecycleRule obsoleteManaged = rule("ai-agent-scaffold-artifact-old", "artifacts/old/", 1);
        when(client.getBucketLifecycle(any(GetBucketLifecycleArgs.class)))
                .thenReturn(new LifecycleConfiguration(List.of(external, obsoleteManaged)));

        new MinioLifecycleManager().reconcile(client, "attachments", List.of(
                new MinioArtifactStorageSettings("app-a", "attachments", Duration.ofDays(1), true),
                new MinioArtifactStorageSettings("app-b", "attachments", Duration.ofHours(25), true),
                new MinioArtifactStorageSettings("app-c", "attachments", Duration.ofDays(2), false)));

        ArgumentCaptor<SetBucketLifecycleArgs> captor = ArgumentCaptor.forClass(SetBucketLifecycleArgs.class);
        verify(client).setBucketLifecycle(captor.capture());
        assertThat(captor.getValue().config().rules())
                .extracting(LifecycleRule::id)
                .containsExactly("external-rule", "ai-agent-scaffold-artifact-app-a");
    }

    @Test
    void noMinioRuntimeDoesNotTouchLifecycleOrCreateClient() {
        MinioLifecycleManager lifecycleManager = mock(MinioLifecycleManager.class);
        ArtifactStorageServiceFactory factory = new ArtifactStorageServiceFactory(
                mock(AttachmentObjectDao.class),
                new ArtifactStorageDeploymentSettings(
                        new ArtifactStorageDeploymentSettings.Minio("", "attachments", "", "", Duration.ofHours(1)),
                        new ArtifactStorageDeploymentSettings.Local(Path.of("data/artifacts"))),
                lifecycleManager);

        factory.reconcileLifecycle(List.of());

        verify(lifecycleManager, never()).reconcile(any(), any(), any());
    }

    private LifecycleRule rule(String id, String prefix, int days) {
        return new LifecycleRule(
                Status.ENABLED,
                null,
                new Expiration((ZonedDateTime) null, days, null),
                new RuleFilter(prefix),
                id,
                null,
                null,
                null);
    }
}
