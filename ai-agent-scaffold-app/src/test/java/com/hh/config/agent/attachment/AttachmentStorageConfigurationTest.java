package com.hh.config.agent.attachment;

import io.minio.DeleteBucketLifecycleArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 验证 MinIO 生命周期规则随附件 TTL 配置变化而保持一致。 */
class AttachmentStorageConfigurationTest {

    @Test
    void clearsExistingLifecycleWhenTtlIsNotWholeDays() throws Exception {
        MinioClient client = mock(MinioClient.class);
        AttachmentStorageProperties properties = properties(Duration.ofHours(25));

        new AttachmentStorageConfiguration().configureLifecycle(client, properties);

        verify(client).deleteBucketLifecycle(any(DeleteBucketLifecycleArgs.class));
        verify(client, never()).setBucketLifecycle(any());
    }

    private AttachmentStorageProperties properties(Duration ttl) {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.setBucket("attachments");
        properties.setTtl(ttl);
        return properties;
    }
}
