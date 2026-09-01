package com.hh.infrastructure.agent.agent.artifact.store.minio;

import com.hh.infrastructure.agent.agent.artifact.store.PersistentArtifactService;
import com.hh.infrastructure.agent.agent.artifact.store.ArtifactObjectMissingException;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioArtifactStorageSettings;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;

import java.io.ByteArrayInputStream;

/** 将单个 Application 的持久化 Artifact 保存到共享 MinIO Bucket 的独立前缀下。 */
public class MinioArtifactService extends PersistentArtifactService {

    private final MinioClient minioClient;
    private final MinioArtifactStorageSettings settings;

    public MinioArtifactService(
            MinioClient minioClient,
            AttachmentObjectDao attachmentObjectDao,
            MinioArtifactStorageSettings settings) {
        super(settings.appName(), "minio", settings.ttl(), attachmentObjectDao);
        this.minioClient = minioClient;
        this.settings = settings;
    }

    public MinioArtifactStorageSettings settings() {
        return settings;
    }

    @Override
    protected void writeObject(String objectKey, byte[] bytes, String mimeType) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(settings.bucket())
                    .object(objectKey)
                    .stream(stream, bytes.length, -1)
                    .contentType(mimeType)
                    .build());
        } catch (Exception error) {
            throw storageFailure(error);
        }
    }

    @Override
    protected byte[] readObject(String objectKey) {
        try (var response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(settings.bucket())
                .object(objectKey)
                .build())) {
            return response.readAllBytes();
        } catch (ErrorResponseException error) {
            // 仅 NoSuchKey 表示对象确实不存在；鉴权、限流和服务端故障仍必须作为存储错误传播。
            if ("NoSuchKey".equals(error.errorResponse().code())) {
                throw new ArtifactObjectMissingException(objectKey, error);
            }
            throw storageFailure(error);
        } catch (Exception error) {
            throw storageFailure(error);
        }
    }

    @Override
    protected void deleteObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(settings.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception error) {
            throw storageFailure(error);
        }
    }

    private AppException storageFailure(Exception error) {
        return new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), error);
    }
}
