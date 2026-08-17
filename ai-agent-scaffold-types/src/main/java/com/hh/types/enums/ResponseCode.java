package com.hh.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),

    AGENT_NOT_FOUND("0003", "Agent not found"),
    MODEL_TIMEOUT("0004", "模型服务响应超时，请稍后重试"),
    MODEL_RATE_LIMITED("0005", "模型服务繁忙，请稍后重试"),
    MODEL_CONFIGURATION_ERROR("0006", "模型服务配置异常，请联系管理员"),
    MODEL_REQUEST_REJECTED("0007", "模型请求未被接受，请检查 Agent 配置"),
    MODEL_UNAVAILABLE("0008", "模型服务暂时不可用，请稍后重试"),
    CONTEXT_COMPACTION_UNAVAILABLE("0009", "上下文压缩暂时不可用，请稍后重试"),
    TOOL_CONFIRMATION_NOT_FOUND("0010", "工具确认单不存在"),
    TOOL_CONFIRMATION_NOT_PENDING("0011", "工具确认单已处理，不能重复决策"),
    TOOL_CONFIRMATION_EXECUTION_UNKNOWN("0012", "工具确认执行状态未知，请核对后再处理"),
    SESSION_NOT_FOUND("0013", "会话不存在或已失效"),
    AUTHENTICATION_REQUIRED("0014", "登录状态无效或已过期"),
    INVALID_CREDENTIALS("0015", "用户名或密码错误"),
    USERNAME_ALREADY_EXISTS("0016", "用户名已存在"),
    ACCESS_DENIED("0017", "无权访问该资源"),
    CONVERSATION_NOT_FOUND("0018", "会话记录不存在"),
    MODEL_CAPABILITY_UNSUPPORTED("0019", "当前模型不支持该类型的输入，请更换支持视觉能力的模型"),
    ;

    private String code;
    private String info;

}
