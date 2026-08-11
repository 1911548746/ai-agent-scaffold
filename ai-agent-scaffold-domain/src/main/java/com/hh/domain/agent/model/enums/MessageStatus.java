package com.hh.domain.agent.model.enums;

/**
 * 会话归档中一条消息的持久化状态。
 *
 * <p>流式 assistant 消息会先以 {@link #PENDING} 落库，只有上游正常结束且收到最终响应后
 * 才能转为 {@link #COMPLETED}，避免将中断内容伪装成正常回复。</p>
 */
public enum MessageStatus {

    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    WAITING_CONFIRMATION("waiting_confirmation");

    private final String value;

    MessageStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
