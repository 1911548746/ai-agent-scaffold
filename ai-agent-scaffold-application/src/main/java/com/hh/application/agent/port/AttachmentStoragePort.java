package com.hh.application.agent.port;

import com.hh.application.agent.model.AttachmentUpload;
import com.hh.domain.agent.model.valobj.AttachmentReference;

import java.util.List;

/**
 * 上传附件到受控 Artifact 存储的端口。
 *
 * <p>端口只向 application 返回安全的 Artifact 引用；二进制读取由 infrastructure 中的 ADK
 * {@code BaseArtifactService} 在模型请求阶段按完整 Session 身份处理。</p>
 */
public interface AttachmentStoragePort {

    List<AttachmentReference> store(
            String appName, String userId, String sessionId, List<AttachmentUpload> uploads);

    void delete(String appName, String userId, String sessionId, List<AttachmentReference> references);
}
