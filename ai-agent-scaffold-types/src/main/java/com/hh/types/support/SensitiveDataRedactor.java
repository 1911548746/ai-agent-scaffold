package com.hh.types.support;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_ROOT_FRAMES = 16;
    private static final int MAX_WRAPPER_FRAMES = 4;
    private static final int MAX_MESSAGE_LENGTH = 4096;
    private static final int MAX_DIAGNOSTIC_LENGTH = 16 * 1024;
    private static final String TRUNCATED = "\n... [异常诊断已截断]";

    private SensitiveDataRedactor() {
    }

    /**
     * 保留字段名以便定位问题，但永远不保留字段值。
     */
    public static String redact(String message) {
        return message == null ? null : SENSITIVE_VALUE.matcher(message).replaceAll(REDACTED_VALUE);
    }

    /**
     * 生成有界的异常诊断信息，避免完整 SDK 堆栈造成超大日志。
     *
     * <p>最深层 cause 的类型、message 和有限栈帧优先保留；外层包装异常只保留少量栈帧。
     * message 仍执行脱敏，且整个结果限制在 16KiB 以内。</p>
     */
    public static String formatStackTrace(Throwable exception) {
        if (exception == null) {
            return "null";
        }

        List<Throwable> causes = new ArrayList<>();
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = exception;

        while (current != null && visited.put(current, Boolean.TRUE) == null
                && causes.size() < MAX_CAUSE_DEPTH) {
            causes.add(current);
            current = current.getCause();
        }

        StringBuilder trace = new StringBuilder(Math.min(MAX_DIAGNOSTIC_LENGTH, 2048));
        boolean truncated = false;
        for (int i = causes.size() - 1; i >= 0; i--) {
            Throwable cause = causes.get(i);
            if (!append(trace, i == causes.size() - 1 ? "Root cause: " : "Caused by: ")) {
                truncated = true;
                break;
            }
            if (!append(trace, cause.getClass().getName())) {
                truncated = true;
                break;
            }
            if (cause.getMessage() != null) {
                String message = cause.getMessage();
                if (message.length() > MAX_MESSAGE_LENGTH) {
                    message = message.substring(0, MAX_MESSAGE_LENGTH) + "...";
                }
                if (!append(trace, ": " + redact(message))) {
                    truncated = true;
                    break;
                }
            }

            StackTraceElement[] frames = cause.getStackTrace();
            int frameLimit = i == causes.size() - 1 ? MAX_ROOT_FRAMES : MAX_WRAPPER_FRAMES;
            int writtenFrames = Math.min(frames.length, frameLimit);
            for (int frameIndex = 0; frameIndex < writtenFrames; frameIndex++) {
                if (!append(trace, "\n\tat " + frames[frameIndex])) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                break;
            }
            if (frames.length > writtenFrames && !append(trace,
                    "\n\t... [省略 " + (frames.length - writtenFrames) + " 个栈帧]")) {
                truncated = true;
                break;
            }
            if (!append(trace, "\n")) {
                truncated = true;
                break;
            }
        }

        if (current != null && visited.containsKey(current)) {
            truncated = true;
        } else if (current != null) {
            truncated = true;
        }
        if (truncated) {
            append(trace, TRUNCATED);
        }
        return trace.toString();
    }

    /** 在固定上限内追加日志片段，避免异常 message 或栈帧突破日志大小限制。 */
    private static boolean append(StringBuilder target, String value) {
        if (target.length() + value.length() > MAX_DIAGNOSTIC_LENGTH) {
            return false;
        }
        target.append(value);
        return true;
    }

}
