package com.hh.application.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次非流式对话的项目内结果。
 *
 * <p>通过不可变集合隔离运行时适配器返回的可变 SDK 容器；调用方只能读取已经提取的助手文本和
 * 脱敏后的待确认请求。</p>
 */
public record ChatResult(
        List<String> contents,
        List<ToolConfirmationRequest> pendingConfirmations) {

    public ChatResult(List<String> contents) {
        this(contents, List.of());
    }

    public ChatResult {
        Objects.requireNonNull(contents, "contents must not be null");
        contents = List.copyOf(contents);
        Objects.requireNonNull(pendingConfirmations, "pendingConfirmations must not be null");
        pendingConfirmations = List.copyOf(pendingConfirmations);
    }

    /**
     * 沿用既有 HTTP 与归档行为：多个最终响应以换行符拼接为一条可展示文本。
     */
    public String joinContents() {
        return String.join("\n", contents);
    }
}
