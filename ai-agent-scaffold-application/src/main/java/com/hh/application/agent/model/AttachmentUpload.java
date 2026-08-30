package com.hh.application.agent.model;

/** 已经由 HTTP 适配器完成格式校验、尚未持久化的附件内容。 */
public record AttachmentUpload(
        String fileName,
        String mimeType,
        byte[] bytes) {
}
