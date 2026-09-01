package com.hh.infrastructure.agent.agent.artifact.store.minio;

import io.minio.DeleteBucketLifecycleArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 对账项目管理的前缀 Lifecycle 规则，并完整保留 Bucket 中的外部规则。 */
public class MinioLifecycleManager {

    static final String MANAGED_RULE_PREFIX = "ai-agent-scaffold-artifact-";

    public void reconcile(
            MinioClient client,
            String bucket,
            Collection<MinioArtifactStorageSettings> applicationSettings) {
        try {
            List<LifecycleRule> existing = readRules(client, bucket);
            List<LifecycleRule> reconciled = existing.stream()
                    .filter(rule -> rule.id() == null || !rule.id().startsWith(MANAGED_RULE_PREFIX))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            applicationSettings.stream()
                    .map(this::managedRule)
                    .flatMap(java.util.Optional::stream)
                    .forEach(reconciled::add);

            if (reconciled.isEmpty()) {
                if (!existing.isEmpty()) {
                    client.deleteBucketLifecycle(DeleteBucketLifecycleArgs.builder().bucket(bucket).build());
                }
                return;
            }
            client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(bucket)
                    .config(new LifecycleConfiguration(List.copyOf(reconciled)))
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("unable to reconcile artifact lifecycle", error);
        }
    }

    private List<LifecycleRule> readRules(MinioClient client, String bucket) throws Exception {
        try {
            LifecycleConfiguration configuration = client.getBucketLifecycle(
                    GetBucketLifecycleArgs.builder().bucket(bucket).build());
            return configuration == null || configuration.rules() == null
                    ? List.of()
                    : List.copyOf(configuration.rules());
        } catch (ErrorResponseException error) {
            String code = error.errorResponse() == null ? null : error.errorResponse().code();
            if ("NoSuchLifecycleConfiguration".equals(code) || "NoSuchLifecycle".equals(code)) {
                return List.of();
            }
            throw error;
        }
    }

    private java.util.Optional<LifecycleRule> managedRule(MinioArtifactStorageSettings settings) {
        Duration ttl = settings.ttl();
        if (!settings.lifecycleEnabled() || ttl == null) {
            return java.util.Optional.empty();
        }
        long days = ttl.toDays();
        if (days < 1 || !ttl.equals(Duration.ofDays(days))) {
            return java.util.Optional.empty();
        }
        if (days > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("artifact ttl is too large for MinIO lifecycle");
        }
        return java.util.Optional.of(new LifecycleRule(
                Status.ENABLED,
                null,
                new Expiration((ZonedDateTime) null, (int) days, null),
                new RuleFilter(settings.objectPrefix()),
                MANAGED_RULE_PREFIX + settings.appName(),
                null,
                null,
                null));
    }
}
