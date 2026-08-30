package com.hh.domain.agent.model.valobj;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 一次对话可引用的附件元数据。
 *
 * <p>artifactName 是 ADK {@code LoadArtifactsTool} 可见的会话级逻辑文件名；对象存储 key、
 * 访问凭据和原始字节均不能越过此边界。</p>
 */
public record AttachmentReference(
        @NotBlank @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH) String artifactName,
        @NotBlank @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH) String fileName,
        @NotBlank @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH) String mimeType,
        @Max(value = RequestPayloadLimits.MAX_INLINE_DATA_BYTES) long size) {
}
