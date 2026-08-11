package com.hh.domain.agent.model.enums;

/**
 * 工具人工确认单的持久化状态。
 *
 * <p>本轮只使用 {@link #PENDING}；其余状态为后续 HITL-02/HITL-03 的状态机预留。</p>
 */
public enum ToolConfirmationStatus {

    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    EXECUTION_UNKNOWN("execution_unknown");

    private final String value;

    ToolConfirmationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
