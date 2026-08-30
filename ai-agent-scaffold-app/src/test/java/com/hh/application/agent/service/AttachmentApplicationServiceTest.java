package com.hh.application.agent.service;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 附件写入必须先完成完整 Session 身份校验，不能创建孤立对象。 */
class AttachmentApplicationServiceTest {

    @Test
    void verifiesAgentAndSessionBeforeStoringAttachments() {
        AgentRuntimePort runtimePort = mock(AgentRuntimePort.class);
        AttachmentStoragePort storagePort = mock(AttachmentStoragePort.class);
        AttachmentUpload upload = new AttachmentUpload("image.png", "image/png", new byte[]{1, 2, 3});
        AttachmentReference reference = new AttachmentReference(
                "attachment-9f2a1742-0c98-4c18-a558-3ee79dfb77ad",
                "image.png",
                "image/png",
                3);
        when(runtimePort.appName("agent-a")).thenReturn("app-a");
        when(storagePort.store("app-a", "user-a", "session-a", List.of(upload))).thenReturn(List.of(reference));

        List<AttachmentReference> stored = new AttachmentApplicationService(runtimePort, storagePort)
                .storeForSession("agent-a", "user-a", "session-a", List.of(upload));

        assertThat(stored).containsExactly(reference);
        InOrder inOrder = inOrder(runtimePort, storagePort);
        inOrder.verify(runtimePort).appName("agent-a");
        inOrder.verify(runtimePort).requireSession("agent-a", "user-a", "session-a");
        inOrder.verify(storagePort).store("app-a", "user-a", "session-a", List.of(upload));
    }
}
