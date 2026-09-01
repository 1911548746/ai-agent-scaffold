package com.hh.infrastructure.agent.agent.artifact;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.infrastructure.agent.agent.artifact.store.local.LocalArtifactService;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实临时目录验证 local 后端的物理文件和元数据回滚。 */
class LocalArtifactServiceTest {

    @TempDir
    Path root;

    @Test
    void persistsLoadsAfterServiceRecreationAndDeletesSession() throws Exception {
        AttachmentObjectDao dao = mock(AttachmentObjectDao.class);
        when(dao.insert(any(AttachmentObjectPO.class))).thenReturn(1);
        LocalArtifactService service = new LocalArtifactService("app-a", root, dao);

        var reference = service.store("app-a", "user-a", "session-a", List.of(
                new AttachmentUpload("note.txt", "text/plain", new byte[]{4, 5, 6}))).get(0);
        ArgumentCaptor<AttachmentObjectPO> metadata = ArgumentCaptor.forClass(AttachmentObjectPO.class);
        org.mockito.Mockito.verify(dao).insert(metadata.capture());
        AttachmentObjectPO object = metadata.getValue();
        assertThat(object.getStorageType()).isEqualTo("local");
        assertThat(object.getExpiresAt()).isNull();
        assertThat(object.getObjectKey()).startsWith("artifacts/app-a/");

        when(dao.selectActiveByArtifactName(
                "local", reference.artifactName(), "app-a", "user-a", "session-a", null))
                .thenReturn(object);
        LocalArtifactService restarted = new LocalArtifactService("app-a", root, dao);
        byte[] loaded = restarted.loadArtifact(
                        "app-a", "user-a", "session-a", reference.artifactName(), null)
                .blockingGet().inlineData().orElseThrow().data().orElseThrow();
        assertThat(loaded).containsExactly(4, 5, 6);

        when(dao.selectBySession("local", "app-a", "user-a", "session-a"))
                .thenReturn(List.of(object));
        restarted.deleteSession("app-a", "user-a", "session-a");
        assertDirectoryEmpty(root.resolve("app-a"));
    }

    @Test
    void removesFileWhenMetadataInsertFails() throws Exception {
        AttachmentObjectDao dao = mock(AttachmentObjectDao.class);
        when(dao.insert(any(AttachmentObjectPO.class))).thenReturn(0);
        LocalArtifactService service = new LocalArtifactService("app-a", root, dao);

        assertThatThrownBy(() -> service.store("app-a", "user-a", "session-a", List.of(
                new AttachmentUpload("note.txt", "text/plain", new byte[]{1}))))
                .isInstanceOf(RuntimeException.class);
        assertDirectoryEmpty(root.resolve("app-a"));
    }

    @Test
    void removesMetadataAndReturnsUploadGuidanceWhenPhysicalFileIsMissing() throws Exception {
        AttachmentObjectDao dao = mock(AttachmentObjectDao.class);
        when(dao.insert(any(AttachmentObjectPO.class))).thenReturn(1);
        LocalArtifactService service = new LocalArtifactService("app-a", root, dao);

        var reference = service.store("app-a", "user-a", "session-a", List.of(
                new AttachmentUpload("note.txt", "text/plain", new byte[]{4, 5, 6}))).get(0);
        ArgumentCaptor<AttachmentObjectPO> metadata = ArgumentCaptor.forClass(AttachmentObjectPO.class);
        verify(dao).insert(metadata.capture());
        AttachmentObjectPO object = metadata.getValue();
        object.setId(7L);
        Files.delete(root.resolve(object.getObjectKey().substring("artifacts/".length())));
        when(dao.selectActiveByArtifactName(
                "local", reference.artifactName(), "app-a", "user-a", "session-a", null))
                .thenReturn(object);

        String guidance = service.loadArtifact("app-a", "user-a", "session-a", reference.artifactName(), null)
                .blockingGet().text().orElseThrow();

        assertThat(guidance).contains("重新上传");
        verify(dao).deleteById(7L);
    }

    @Test
    void rejectsApplicationPathTraversal() {
        assertThatThrownBy(() -> new LocalArtifactService("../escape", root, mock(AttachmentObjectDao.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes configured root");
    }

    private void assertDirectoryEmpty(Path directory) throws Exception {
        try (var entries = Files.walk(directory)) {
            assertThat(entries.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }
}
