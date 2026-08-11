package com.hh.application.agent.model;

import java.util.Objects;

/**
 * 面向调用方的单个对话输出片段。
 *
 * <p>它刻意不携带 ADK {@code Event} 或供应商的原始事件，以免 SDK 事件结构穿透到用例和 HTTP
 * 适配器；除文本片段外，仅允许表达脱敏后的待确认工具请求。</p>
 */
public record ChatEvent(String content, ToolConfirmationRequest confirmation) {

    public ChatEvent(String content) {
        this(content, null);
    }

    public ChatEvent {
        // 空文本仍是合法片段，但不能接受 null，避免下游 SSE 编码和归档拼接出现歧义。
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ChatEvent confirmation(ToolConfirmationRequest confirmation) {
        return new ChatEvent("", Objects.requireNonNull(confirmation, "confirmation must not be null"));
    }

    public boolean isToolConfirmation() {
        return confirmation != null;
    }
}
