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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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

    private MinioArtifactService service(MinioClient minioClient, AttachmentObjectDao attachmentObjectDao) {
        return new MinioArtifactService(
                minioClient,
                attachmentObjectDao,
                new MinioAttachmentStorageSettings("attachments", Duration.ofHours(24)));
    }
}
