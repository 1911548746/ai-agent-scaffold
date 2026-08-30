package com.hh.application.agent.usecase;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.domain.agent.model.valobj.AttachmentReference;

import java.util.List;

/** 多模态 HTTP 上传进入对话前的附件保存用例。 */
public interface AttachmentUseCase {

    List<AttachmentReference> storeForSession(
            String agentId, String userId, String sessionId, List<AttachmentUpload> uploads);
}
