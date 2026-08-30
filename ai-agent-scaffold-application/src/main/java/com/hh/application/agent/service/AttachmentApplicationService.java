package com.hh.application.agent.service;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.application.agent.usecase.AttachmentUseCase;
import com.hh.domain.agent.model.valobj.AttachmentReference;

import java.util.List;

/** 在写入附件前校验 Agent、认证用户和 Session 的归属。 */
public class AttachmentApplicationService implements AttachmentUseCase {

    private final AgentRuntimePort agentRuntimePort;
    private final AttachmentStoragePort attachmentStoragePort;

    public AttachmentApplicationService(
            AgentRuntimePort agentRuntimePort,
            AttachmentStoragePort attachmentStoragePort) {
        this.agentRuntimePort = agentRuntimePort;
        this.attachmentStoragePort = attachmentStoragePort;
    }

    @Override
    public List<AttachmentReference> storeForSession(
            String agentId, String userId, String sessionId, List<AttachmentUpload> uploads) {
        String appName = agentRuntimePort.appName(agentId);
        agentRuntimePort.requireSession(agentId, userId, sessionId);
        return attachmentStoragePort.store(appName, userId, sessionId, uploads);
    }
}
