package com.hh.infrastructure.agent.agent.artifact.routing;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageService;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.infrastructure.agent.agent.runtime.RuntimeLease;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.util.List;

/** 将 application 附件操作路由到已发布 Runtime 的同一 ArtifactService 实例。 */
public class RuntimeAttachmentStoragePort implements AttachmentStoragePort {

    private final AdkAgentRuntimeRegistry runtimeRegistry;

    public RuntimeAttachmentStoragePort(AdkAgentRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override
    public List<AttachmentReference> store(
            String appName, String userId, String sessionId, List<AttachmentUpload> uploads) {
        try (RuntimeLease lease = acquireRuntime(appName)) {
            return requireService(lease).store(appName, userId, sessionId, uploads);
        }
    }

    @Override
    public void delete(
            String appName, String userId, String sessionId, List<AttachmentReference> references) {
        try (RuntimeLease lease = acquireRuntime(appName)) {
            requireService(lease).delete(appName, userId, sessionId, references);
        }
    }

    @Override
    public void deleteSession(String appName, String userId, String sessionId) {
        try (RuntimeLease lease = acquireRuntime(appName)) {
            requireService(lease).deleteSession(appName, userId, sessionId);
        }
    }

    private RuntimeLease acquireRuntime(String appName) {
        return runtimeRegistry.acquireByAppName(appName)
                .orElseThrow(() -> new AppException(ResponseCode.AGENT_NOT_FOUND));
    }

    private ArtifactStorageService requireService(RuntimeLease lease) {
        Object service = lease.runtime().runner().artifactService();
        if (service instanceof ArtifactStorageService artifactStorageService) {
            return artifactStorageService;
        }
        throw new IllegalStateException("published Runtime does not use a routable ArtifactService");
    }
}
