package com.hh.infrastructure.agent.agent.artifact;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import com.hh.application.agent.model.AttachmentUpload;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.infrastructure.agent.dao.po.AttachmentObjectPO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MinIO 对象存储与 ADK ArtifactService 的统一适配器。
 *
 * <p>对象存储只保存随机 object key；MySQL 元数据始终以 appName、userId、sessionId 约束访问范围。
 * 因此 ADK 的 {@code LoadArtifactsTool} 即使由模型触发，也只能读取当前会话中仍有效的 Artifact。</p>
 */
public class MinioArtifactService implements BaseArtifactService, AttachmentStoragePort {

    private static final String ARTIFACT_NAME_PREFIX = "attachment-";
    private static final String OBJECT_KEY_PREFIX = "artifacts/";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final AttachmentObjectDao attachmentObjectDao;
    private final MinioAttachmentStorageSettings settings;

    public MinioArtifactService(
            MinioClient minioClient,
            AttachmentObjectDao attachmentObjectDao,
            MinioAttachmentStorageSettings settings) {
        this.minioClient = minioClient;
        this.attachmentObjectDao = attachmentObjectDao;
        this.settings = settings;
    }

    /**
     * HTTP 上传入口使用的 application 端口。
     *
     * <p>每个上传均分配新的 Artifact 名称，避免用户输入的文件名成为可猜测的读取凭据，也避免上传
     * 场景出现同名版本竞争。原始文件名只作为受控元数据保存。</p>
     */
    @Override
    public List<AttachmentReference> store(
            String appName, String userId, String sessionId, List<AttachmentUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }

        List<AttachmentReference> stored = new ArrayList<>(uploads.size());
        try {
            for (AttachmentUpload upload : uploads) {
                String artifactName = ARTIFACT_NAME_PREFIX + UUID.randomUUID();
                Part artifact = Part.fromBytes(upload.bytes(), upload.mimeType());
                saveArtifactInternal(
                        appName,
                        userId,
                        sessionId,
                        artifactName,
                        upload.fileName(),
                        artifact,
                        0);
                stored.add(new AttachmentReference(
                        artifactName, upload.fileName(), upload.mimeType(), upload.bytes().length));
            }
            return List.copyOf(stored);
        } catch (RuntimeException error) {
            // 本次批量上传任一项失败时，撤销已经可见的 Artifact，避免产生半套可引用数据。
            delete(appName, userId, sessionId, stored);
            throw error;
        }
    }

    /**
     * ADK 的通用保存入口。
     *
     * <p>本期的 HTTP 上传只使用随机唯一名称和版本 0；此处仍保留同名 Artifact 的递增版本，
     * 供后续 ADK 工具或工作流保存派生文件使用。</p>
     */
    @Override
    public Single<Integer> saveArtifact(
            String appName, String userId, String sessionId, String filename, Part artifact) {
        return Single.fromCallable(() -> {
            int version = nextArtifactVersion(appName, userId, sessionId, filename);
            return saveArtifactInternal(
                    appName, userId, sessionId, filename, filename, artifact, version);
        });
    }

    /**
     * 将 Artifact 还原为仅在当前 LLM 请求中临时存在的 Part。
     *
     * <p>空结果统一表示 Artifact 不存在、已过期或不属于该 Session，避免给模型或调用方暴露归属信息。</p>
     */
    @Override
    public Maybe<Part> loadArtifact(
            String appName, String userId, String sessionId, String filename, Integer version) {
        return Maybe.fromCallable(() -> {
            AttachmentObjectPO object = attachmentObjectDao.selectActiveByArtifactName(
                    filename, appName, userId, sessionId, version);
            if (object == null) {
                return null;
            }
            return loadObject(object);
        });
    }

    /** 仅向 ADK 暴露逻辑文件名，绝不能暴露 bucket、object key 或可访问 URL。 */
    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId, String sessionId) {
        return Single.fromCallable(() -> ListArtifactsResponse.builder()
                .filenames(attachmentObjectDao.selectActiveArtifactNames(appName, userId, sessionId))
                .build());
    }

    /** 删除指定 Artifact 的所有版本；生命周期自动清理属于后续 DATA-08。 */
    @Override
    public Completable deleteArtifact(String appName, String userId, String sessionId, String filename) {
        return Completable.fromAction(() -> deleteArtifactVersions(appName, userId, sessionId, filename));
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(
            String appName, String userId, String sessionId, String filename) {
        return Single.fromCallable(() -> ImmutableList.copyOf(
                attachmentObjectDao.selectArtifactVersions(filename, appName, userId, sessionId)));
    }

    /** application 删除入口复用 ADK 的同一份会话归属检查和对象删除逻辑。 */
    @Override
    public void delete(String appName, String userId, String sessionId, List<AttachmentReference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        for (AttachmentReference reference : references) {
            deleteArtifactVersions(appName, userId, sessionId, reference.artifactName());
        }
    }

    private int saveArtifactInternal(
            String appName,
            String userId,
            String sessionId,
            String artifactName,
            String fileName,
            Part artifact,
            int artifactVersion) {
        Blob inlineData = artifact.inlineData().orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER));
        byte[] bytes = inlineData.data().orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER));
        String mimeType = inlineData.mimeType().orElse(DEFAULT_MIME_TYPE);
        String objectKey = OBJECT_KEY_PREFIX + UUID.randomUUID();

        // 存到 MinIO
        putObject(objectKey, bytes, mimeType);

        try {
            AttachmentObjectPO object = AttachmentObjectPO.builder()
                    .artifactName(artifactName)
                    .artifactVersion(artifactVersion)
                    .appName(appName)
                    .userId(userId)
                    .sessionId(sessionId)
                    .objectKey(objectKey)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .sizeBytes((long) bytes.length)
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plus(settings.ttl()))
                    .build();
            if (attachmentObjectDao.insert(object) != 1) {
                throw new AppException(ResponseCode.UN_ERROR);
            }
            return artifactVersion;
        } catch (RuntimeException error) {
            // 元数据未提交时，立即回收对象，不能让后续调用绕过 Session 归属读取它。
            removeObjectQuietly(objectKey);
            throw error;
        }
    }

    private Part loadObject(AttachmentObjectPO object) {
        try (var response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(settings.bucket())
                .object(object.getObjectKey())
                .build())) {
            byte[] bytes = response.readAllBytes();
            if (bytes.length != object.getSizeBytes()) {
                throw new AppException(ResponseCode.UN_ERROR);
            }
            return Part.fromBytes(bytes, object.getMimeType());
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), error);
        }
    }

    private int nextArtifactVersion(String appName, String userId, String sessionId, String artifactName) {
        List<Integer> versions = attachmentObjectDao.selectArtifactVersions(artifactName, appName, userId, sessionId);
        return (versions == null ? List.<Integer>of() : versions).stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
    }

    private void deleteArtifactVersions(String appName, String userId, String sessionId, String artifactName) {
        List<AttachmentObjectPO> objects = attachmentObjectDao.selectByArtifactName(
                artifactName, appName, userId, sessionId);
        for (AttachmentObjectPO object : objects) {
            removeObject(object.getObjectKey());
        }
        attachmentObjectDao.deleteByArtifactName(artifactName, appName, userId, sessionId);
    }

    private void putObject(String objectKey, byte[] bytes, String mimeType) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(settings.bucket())
                    .object(objectKey)
                    .stream(stream, bytes.length, -1)
                    .contentType(mimeType)
                    .build());
        } catch (Exception error) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), error);
        }
    }

    private void removeObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(settings.bucket()).object(objectKey).build());
        } catch (Exception error) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), error);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(settings.bucket()).object(objectKey).build());
        } catch (Exception ignored) {
            // 清理失败不会覆盖原始异常；DATA-08 的生命周期任务负责处理遗留对象。
        }
    }
}
