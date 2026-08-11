package com.hh.domain.agent.model.enums;

/**
 * 工具人工确认单的持久化状态。
 *
 * <p>HITL-02 使用 pending、processing、completed、rejected 和 execution_unknown；expired
 * 为后续过期策略预留。</p>
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

    public static ToolConfirmationStatus fromValue(String value) {
        for (ToolConfirmationStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工具确认状态");
    }
}
