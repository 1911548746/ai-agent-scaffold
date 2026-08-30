package com.hh.config.agent.attachment;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.hh.application.agent.model.AttachmentUpload;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 未显式启用对象存储时不建立网络连接，附件访问统一返回稳定的业务失败。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "ai.agent.attachment",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class UnavailableAttachmentStorageConfiguration {

    @Bean
    public AttachmentStoragePort unavailableAttachmentStoragePort() {
        return new AttachmentStoragePort() {
            @Override
            public List<AttachmentReference> store(
                    String appName, String userId, String sessionId, List<AttachmentUpload> uploads) {
                throw new AppException(ResponseCode.UN_ERROR);
            }

            @Override
            public void delete(
                    String appName, String userId, String sessionId, List<AttachmentReference> references) {
                throw new AppException(ResponseCode.UN_ERROR);
            }
        };
    }

    /** 未启用对象存储时，普通文本 Agent 仍可使用 ADK 的内存 ArtifactService 正常运行。 */
    @Bean
    public BaseArtifactService artifactService() {
        return new InMemoryArtifactService();
    }
}
