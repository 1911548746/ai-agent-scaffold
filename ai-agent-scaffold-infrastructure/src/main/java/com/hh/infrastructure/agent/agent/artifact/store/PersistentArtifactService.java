package com.hh.infrastructure.agent.agent.artifact.store;

import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import com.hh.application.agent.model.AttachmentUpload;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageService;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MinIO 与本地文件后端共享的元数据、版本和 Session 归属实现。 */
public abstract class PersistentArtifactService implements ArtifactStorageService {

    private static final String ARTIFACT_NAME_PREFIX = "attachment-";
    private static final String OBJECT_KEY_PREFIX = "artifacts/";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private static final String MISSING_ARTIFACT_MESSAGE = "附件已不可用，请提示用户重新上传后再继续处理。";

    private final String appName;
    private final String storageType;
    private final Duration ttl;
    private final AttachmentObjectDao attachmentObjectDao;

    protected PersistentArtifactService(
            String appName,
            String storageType,
            Duration ttl,
            AttachmentObjectDao attachmentObjectDao) {
        this.appName = appName;
        this.storageType = storageType;
        this.ttl = ttl;
        this.attachmentObjectDao = attachmentObjectDao;
    }

    @Override
    public final String appName() {
        return appName;
    }

    public final String storageType() {
        return storageType;
    }

    public final Duration ttl() {
        return ttl;
    }

    @Override
    public final List<AttachmentReference> store(
            String appName, String userId, String sessionId, List<AttachmentUpload> uploads) {
        requireAppName(appName);
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }
        List<AttachmentReference> stored = new ArrayList<>(uploads.size());
        try {
            for (AttachmentUpload upload : uploads) {
                String artifactName = ARTIFACT_NAME_PREFIX + UUID.randomUUID();
                saveArtifactInternal(userId, sessionId, artifactName, upload.fileName(),
                        Part.fromBytes(upload.bytes(), upload.mimeType()), 0);
                stored.add(new AttachmentReference(
                        artifactName, upload.fileName(), upload.mimeType(), upload.bytes().length));
            }
            return List.copyOf(stored);
        } catch (RuntimeException error) {
            delete(appName, userId, sessionId, stored);
            throw error;
        }
    }

    @Override
    public final Single<Integer> saveArtifact(
            String appName, String userId, String sessionId, String filename, Part artifact) {
        requireAppName(appName);
        return Single.fromCallable(() -> saveArtifactInternal(
                userId, sessionId, filename, filename, artifact,
                nextArtifactVersion(userId, sessionId, filename)));
    }

    @Override
    public final Maybe<Part> loadArtifact(
            String appName, String userId, String sessionId, String filename, Integer version) {
        requireAppName(appName);
        return Maybe.fromCallable(() -> {
            AttachmentObjectPO object = attachmentObjectDao.selectActiveByArtifactName(
                    storageType, filename, appName, userId, sessionId, version);
            if (object == null) {
                return null;
            }
            byte[] bytes;
            try {
                bytes = readObject(object.getObjectKey());
            } catch (ArtifactObjectMissingException missing) {
                // 物理对象已被生命周期或外部操作删除时，清理当前版本元数据，避免后续继续暴露失效附件。
                attachmentObjectDao.deleteById(object.getId());
                // LoadArtifactsTool 会将该文本临时附加到本轮 LlmRequest，使模型明确要求重新上传而非臆测内容。
                return Part.fromText(MISSING_ARTIFACT_MESSAGE);
            }
            if (bytes.length != object.getSizeBytes()) {
                throw new AppException(ResponseCode.UN_ERROR);
            }
            return Part.fromBytes(bytes, object.getMimeType());
        });
    }

    @Override
    public final Single<ListArtifactsResponse> listArtifactKeys(
            String appName, String userId, String sessionId) {
        requireAppName(appName);
        return Single.fromCallable(() -> ListArtifactsResponse.builder()
                .filenames(attachmentObjectDao.selectActiveArtifactNames(
                        storageType, appName, userId, sessionId))
                .build());
    }

    @Override
    public final Completable deleteArtifact(
            String appName, String userId, String sessionId, String filename) {
        requireAppName(appName);
        return Completable.fromAction(() -> deleteArtifactVersions(userId, sessionId, filename));
    }

    @Override
    public final Single<ImmutableList<Integer>> listVersions(
            String appName, String userId, String sessionId, String filename) {
        requireAppName(appName);
        return Single.fromCallable(() -> ImmutableList.copyOf(
                attachmentObjectDao.selectArtifactVersions(
                        storageType, filename, appName, userId, sessionId)));
    }

    @Override
    public final void delete(
            String appName, String userId, String sessionId, List<AttachmentReference> references) {
        requireAppName(appName);
        if (references == null) {
            return;
        }
        references.forEach(reference -> deleteArtifactVersions(
                userId, sessionId, reference.artifactName()));
    }

    @Override
    public final void deleteSession(String appName, String userId, String sessionId) {
        requireAppName(appName);
        List<AttachmentObjectPO> objects = attachmentObjectDao.selectBySession(
                storageType, appName, userId, sessionId);
        for (AttachmentObjectPO object : objects) {
            deleteObject(object.getObjectKey());
            attachmentObjectDao.deleteById(object.getId());
        }
    }

    /** 只供带 TTL 的持久化后端调用；对象删除失败时保留元数据等待下一轮重试。 */
    public final int cleanupExpired(int batchSize) {
        if (ttl == null) {
            return 0;
        }
        List<AttachmentObjectPO> objects = attachmentObjectDao.selectExpired(
                storageType, appName, LocalDateTime.now(), batchSize);
        int deleted = 0;
        for (AttachmentObjectPO object : objects) {
            deleteObject(object.getObjectKey());
            if (attachmentObjectDao.deleteById(object.getId()) == 1) {
                deleted++;
            }
        }
        return deleted;
    }

    protected abstract void writeObject(String objectKey, byte[] bytes, String mimeType);

    protected abstract byte[] readObject(String objectKey);

    protected abstract void deleteObject(String objectKey);

    private int saveArtifactInternal(
            String userId,
            String sessionId,
            String artifactName,
            String fileName,
            Part artifact,
            int artifactVersion) {
        Blob inlineData = artifact.inlineData().orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER));
        byte[] bytes = inlineData.data().orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER));
        String mimeType = inlineData.mimeType().orElse(DEFAULT_MIME_TYPE);
        String objectKey = OBJECT_KEY_PREFIX + appName + "/"
                + sessionId + "/"
                + UUID.randomUUID() + fileName.substring(fileName.lastIndexOf('.'));
        writeObject(objectKey, bytes, mimeType);

        try {
            LocalDateTime now = LocalDateTime.now();
            AttachmentObjectPO object = AttachmentObjectPO.builder()
                    .storageType(storageType)
                    .artifactName(artifactName)
                    .artifactVersion(artifactVersion)
                    .appName(appName)
                    .userId(userId)
                    .sessionId(sessionId)
                    .objectKey(objectKey)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .sizeBytes((long) bytes.length)
                    .createdAt(now)
                    .expiresAt(ttl == null ? null : now.plus(ttl))
                    .build();
            if (attachmentObjectDao.insert(object) != 1) {
                throw new AppException(ResponseCode.UN_ERROR);
            }
            return artifactVersion;
        } catch (RuntimeException error) {
            try {
                deleteObject(objectKey);
            } catch (RuntimeException ignored) {
                // 保留原始元数据写入异常；物理残留只能由后端运维手段处理。
            }
            throw error;
        }
    }

    private int nextArtifactVersion(String userId, String sessionId, String artifactName) {
        List<Integer> versions = attachmentObjectDao.selectArtifactVersions(
                storageType, artifactName, appName, userId, sessionId);
        return (versions == null ? List.<Integer>of() : versions).stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
    }

    private void deleteArtifactVersions(String userId, String sessionId, String artifactName) {
        List<AttachmentObjectPO> objects = attachmentObjectDao.selectByArtifactName(
                storageType, artifactName, appName, userId, sessionId);
        for (AttachmentObjectPO object : objects) {
            deleteObject(object.getObjectKey());
        }
        attachmentObjectDao.deleteByArtifactName(
                storageType, artifactName, appName, userId, sessionId);
    }

    private void requireAppName(String requestedAppName) {
        if (!appName.equals(requestedAppName)) {
            throw new IllegalArgumentException("artifact service does not belong to appName " + requestedAppName);
        }
    }
}
