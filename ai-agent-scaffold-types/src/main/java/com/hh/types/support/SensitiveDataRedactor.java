package com.hh.types.support;

import java.util.IdentityHashMap;
import java.util.regex.Pattern;

/**
 * 对异常和配置文本中的常见凭据字段进行脱敏。
 *
 * <p>该工具只依赖 JDK，供各模块的日志边界复用，避免 Controller 与异常处理器各自维护
 * 不一致的规则。它只应用于服务端日志；客户端响应仍应使用固定的公开文案。</p>
 */
public final class SensitiveDataRedactor {

    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)([\\\"']?(?:api[-_]?key|authorization|mcp[-_]?headers?|token|password|secret)[\\\"']?\\s*[:=]\\s*)"
                    + "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)"
    );

    private static final String REDACTED_VALUE = "$1***";

    private SensitiveDataRedactor() {
    }

    /**
     * 保留字段名以便定位问题，但永远不保留字段值。
     */
    public static String redact(String message) {
        return message == null ? null : SENSITIVE_VALUE.matcher(message).replaceAll(REDACTED_VALUE);
    }

    /**
     * 生成可用于日志的异常链和堆栈，避免第三方异常中的凭据未经处理直接进入日志。
     */
    public static String formatStackTrace(Throwable exception) {
        StringBuilder trace = new StringBuilder();
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = exception;
        boolean rootException = true;

        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            if (!rootException) {
                trace.append("Caused by: ");
            }
            trace.append(current.getClass().getName());
            if (current.getMessage() != null) {
                trace.append(": ").append(redact(current.getMessage()));
            }
            for (StackTraceElement stackTraceElement : current.getStackTrace()) {
                trace.append("\n\tat ").append(stackTraceElement);
            }
            trace.append('\n');
            current = current.getCause();
            rootException = false;
        }

        // 少数自定义异常可能形成 cause 环，日志格式化不能因此陷入无限循环。
        if (current != null) {
            trace.append("Caused by: [检测到循环异常引用，后续原因链已省略]");
        }
        return trace.toString();
    }

}
