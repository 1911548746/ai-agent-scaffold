package com.hh.trigger.http.agent.parser;

/**
 * 已完成格式校验的附件内容。该结果对象不暴露 Spring Multipart 类型，便于后续映射到 application 命令。
 */
public record ParsedAttachment(String fileName, byte[] bytes, String mimeType) {
}
