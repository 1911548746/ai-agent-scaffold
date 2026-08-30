package com.hh.infrastructure.agent.adapter.repository;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.agent.artifact.MinioArtifactService;
import com.hh.infrastructure.agent.agent.artifact.MinioAttachmentStorageSettings;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证对象存储适配器的 Artifact 元数据边界，不连接真实 MinIO 或数据库。 */
class MinioArtifactServiceTest {

    @Test
    void storesUploadAsRandomArtifactAndKeepsOriginalNameOnlyInMetadata() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        when(attachmentObjectDao.insert(any(AttachmentObjectPO.class))).thenReturn(1);
        MinioArtifactService service = service(minioClient, attachmentObjectDao);

        AttachmentReference reference = service.store("app-a", "user-a", "session-a", List.of(
                        new AttachmentUpload("diagram.png", "image/png", new byte[]{1, 2, 3})))
                .get(0);

        ArgumentCaptor<AttachmentObjectPO> metadata = ArgumentCaptor.forClass(AttachmentObjectPO.class);
        verify(attachmentObjectDao).insert(metadata.capture());
        assertThat(reference.artifactName()).startsWith("attachment-");
        assertThat(reference.artifactName()).doesNotContain("diagram.png");
        assertThat(metadata.getValue())
                .extracting(
                        AttachmentObjectPO::getAppName,
                        AttachmentObjectPO::getUserId,
                        AttachmentObjectPO::getSessionId,
                        AttachmentObjectPO::getArtifactName,
                        AttachmentObjectPO::getArtifactVersion,
                        AttachmentObjectPO::getFileName)
                .containsExactly("app-a", "user-a", "session-a", reference.artifactName(), 0, "diagram.png");
        assertThat(metadata.getValue().getObjectKey()).startsWith("artifacts/");
        verify(minioClient).putObject(any());
    }

    @Test
    void listsOnlyArtifactNamesVisibleToTheCurrentSession() {
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        when(attachmentObjectDao.selectActiveArtifactNames("app-a", "user-a", "session-a"))
                .thenReturn(List.of("attachment-a", "attachment-b"));
        MinioArtifactService service = service(mock(MinioClient.class), attachmentObjectDao);

        List<String> artifactNames = service.listArtifactKeys("app-a", "user-a", "session-a")
                .blockingGet()
                .filenames();

        assertThat(artifactNames).containsExactly("attachment-a", "attachment-b");
        verify(attachmentObjectDao).selectActiveArtifactNames("app-a", "user-a", "session-a");
    }

    @Test
    void treatsMissingOrExpiredArtifactAsNotLoadable() {
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        when(attachmentObjectDao.selectActiveByArtifactName(
                eq("attachment-a"), eq("app-a"), eq("user-a"), eq("session-a"), isNull()))
                .thenReturn(null);
        MinioArtifactService service = service(mock(MinioClient.class), attachmentObjectDao);

        service.loadArtifact("app-a", "user-a", "session-a", "attachment-a", null)
                .test()
                .assertNoValues()
                .assertComplete();
    }

    @Test
    void cleanupExpiredDeletesObjectBeforeMetadata() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        AttachmentObjectPO object = AttachmentObjectPO.builder()
                .id(11L)
                .objectKey("artifacts/object-a")
                .build();
        when(attachmentObjectDao.selectExpired(any(), eq(100))).thenReturn(List.of(object));
        when(attachmentObjectDao.deleteById(11L)).thenReturn(1);
        MinioArtifactService service = service(minioClient, attachmentObjectDao);

        assertThat(service.cleanupExpired(100)).isEqualTo(1);

        var order = inOrder(minioClient, attachmentObjectDao);
        order.verify(minioClient).removeObject(any());
        order.verify(attachmentObjectDao).deleteById(11L);
    }

    @Test
    void cleanupExpiredKeepsMetadataWhenObjectDeleteFails() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        AttachmentObjectPO object = AttachmentObjectPO.builder()
                .id(12L)
                .objectKey("artifacts/object-b")
                .build();
        when(attachmentObjectDao.selectExpired(any(), eq(100))).thenReturn(List.of(object));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(minioClient).removeObject(any());
        MinioArtifactService service = service(minioClient, attachmentObjectDao);

        assertThatThrownBy(() -> service.cleanupExpired(100))
                .isInstanceOf(RuntimeException.class);
        verify(attachmentObjectDao, never()).deleteById(12L);
    }

    @Test
    void deleteSessionCleansAllVersionsByCompleteSessionIdentity() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        AttachmentObjectDao attachmentObjectDao = mock(AttachmentObjectDao.class);
        AttachmentObjectPO first = AttachmentObjectPO.builder().id(21L).objectKey("artifacts/a").build();
        AttachmentObjectPO second = AttachmentObjectPO.builder().id(22L).objectKey("artifacts/b").build();
        when(attachmentObjectDao.selectBySession("app-a", "user-a", "session-a"))
                .thenReturn(List.of(first, second));
        when(attachmentObjectDao.deleteById(org.mockito.ArgumentMatchers.<java.io.Serializable>any())).thenReturn(1);
        MinioArtifactService service = service(minioClient, attachmentObjectDao);

        service.deleteSession("app-a", "user-a", "session-a");

        verify(attachmentObjectDao).selectBySession("app-a", "user-a", "session-a");
        verify(attachmentObjectDao).deleteById(21L);
        verify(attachmentObjectDao).deleteById(22L);
        verify(minioClient, org.mockito.Mockito.times(2)).removeObject(any());
    }

    private MinioArtifactService service(MinioClient minioClient, AttachmentObjectDao attachmentObjectDao) {
        return new MinioArtifactService(
                minioClient,
                attachmentObjectDao,
                new MinioAttachmentStorageSettings("attachments", Duration.ofHours(24)));
    }
}
