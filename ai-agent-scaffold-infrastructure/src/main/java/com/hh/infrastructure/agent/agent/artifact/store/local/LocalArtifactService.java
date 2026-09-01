package com.hh.infrastructure.agent.agent.artifact.store.local;

import com.hh.infrastructure.agent.agent.artifact.store.PersistentArtifactService;
import com.hh.infrastructure.agent.agent.artifact.store.ArtifactObjectMissingException;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 将 Artifact 保存到部署配置根目录下的 Application 子目录。 */
public class LocalArtifactService extends PersistentArtifactService {

    private static final String OBJECT_KEY_PREFIX = "artifacts/";

    private final Path rootDirectory;

    public LocalArtifactService(String appName, Path configuredRoot, AttachmentObjectDao attachmentObjectDao) {
        super(appName, "local", null, attachmentObjectDao);
        this.rootDirectory = configuredRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolveDirectory(appName));
        } catch (IOException error) {
            throw storageFailure(error);
        }
    }

    @Override
    protected void writeObject(String objectKey, byte[] bytes, String mimeType) {
        Path target = resolveObject(objectKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            // object key 按 Session 分层；首次上传到该会话前需要创建对应的物理目录。
            Files.createDirectories(target.getParent());
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 保留原始写入异常。
            }
            throw storageFailure(error);
        }
    }

    @Override
    protected byte[] readObject(String objectKey) {
        try {
            return Files.readAllBytes(resolveObject(objectKey));
        } catch (NoSuchFileException missing) {
            // 文件被外部清理时，交由父类删除失效元数据并向模型说明需要重新上传。
            throw new ArtifactObjectMissingException(objectKey, missing);
        } catch (IOException error) {
            throw storageFailure(error);
        }
    }

    @Override
    protected void deleteObject(String objectKey) {
        try {
            Files.deleteIfExists(resolveObject(objectKey));
        } catch (IOException error) {
            throw storageFailure(error);
        }
    }

    private Path resolveDirectory(String appName) {
        return requireInsideRoot(rootDirectory.resolve(appName).normalize());
    }

    private Path resolveObject(String objectKey) {
        if (!objectKey.startsWith(OBJECT_KEY_PREFIX)) {
            throw new IllegalArgumentException("invalid local artifact object key");
        }
        return requireInsideRoot(rootDirectory.resolve(objectKey.substring(OBJECT_KEY_PREFIX.length())).normalize());
    }

    private Path requireInsideRoot(Path path) {
        if (!path.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("local artifact path escapes configured root");
        }
        return path;
    }

    private AppException storageFailure(IOException error) {
        return new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), error);
    }
}
