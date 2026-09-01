package com.hh.infrastructure.agent.agent.artifact.store.memory;

import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import com.hh.application.agent.model.AttachmentUpload;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 每个 Runtime 独占的内存 ArtifactService；进程重启后数据自然消失。 */
public class MemoryArtifactService implements ArtifactStorageService {

    private static final String ARTIFACT_NAME_PREFIX = "attachment-";

    private final String appName;
    private final InMemoryArtifactService delegate = new InMemoryArtifactService();

    public MemoryArtifactService(String appName) {
        this.appName = appName;
    }

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public List<AttachmentReference> store(
            String appName, String userId, String sessionId, List<AttachmentUpload> uploads) {
        requireAppName(appName);
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }
        List<AttachmentReference> stored = new ArrayList<>(uploads.size());
        try {
            for (AttachmentUpload upload : uploads) {
                String artifactName = ARTIFACT_NAME_PREFIX + UUID.randomUUID();
                delegate.saveArtifact(appName, userId, sessionId, artifactName,
                        Part.fromBytes(upload.bytes(), upload.mimeType())).blockingGet();
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
    public void delete(
            String appName, String userId, String sessionId, List<AttachmentReference> references) {
        requireAppName(appName);
        if (references == null) {
            return;
        }
        references.forEach(reference -> delegate.deleteArtifact(
                appName, userId, sessionId, reference.artifactName()).blockingAwait());
    }

    @Override
    public void deleteSession(String appName, String userId, String sessionId) {
        requireAppName(appName);
        List<String> names = delegate.listArtifactKeys(appName, userId, sessionId)
                .blockingGet().filenames();
        names.forEach(name -> delegate.deleteArtifact(appName, userId, sessionId, name).blockingAwait());
    }

    @Override
    public Single<Integer> saveArtifact(
            String appName, String userId, String sessionId, String filename, Part artifact) {
        requireAppName(appName);
        return delegate.saveArtifact(appName, userId, sessionId, filename, artifact);
    }

    @Override
    public Maybe<Part> loadArtifact(
            String appName, String userId, String sessionId, String filename, Integer version) {
        requireAppName(appName);
        return delegate.loadArtifact(appName, userId, sessionId, filename, version);
    }

    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId, String sessionId) {
        requireAppName(appName);
        return delegate.listArtifactKeys(appName, userId, sessionId);
    }

    @Override
    public Completable deleteArtifact(String appName, String userId, String sessionId, String filename) {
        requireAppName(appName);
        return delegate.deleteArtifact(appName, userId, sessionId, filename);
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(
            String appName, String userId, String sessionId, String filename) {
        requireAppName(appName);
        return delegate.listVersions(appName, userId, sessionId, filename);
    }

    private void requireAppName(String requestedAppName) {
        if (!appName.equals(requestedAppName)) {
            throw new IllegalArgumentException("artifact service does not belong to appName " + requestedAppName);
        }
    }
}
