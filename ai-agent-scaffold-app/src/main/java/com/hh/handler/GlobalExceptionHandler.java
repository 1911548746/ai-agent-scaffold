package com.hh.handler;

import com.hh.api.response.Response;
import com.hh.types.exception.AppException;
import com.hh.types.enums.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.IdentityHashMap;
import java.util.regex.Pattern;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 日志可以保留供应商错误原因用于排障，但不能把形如 apiKey、Authorization 的键值写入日志。
     */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)([\\\"']?(?:api[-_]?key|authorization|mcp[-_]?headers?|token|password|secret)[\\\"']?\\s*[:=]\\s*)"
                    + "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)"
    );

    private static final String REDACTED_VALUE = "$1***";

    /**
     * 捕获可预期的业务异常，并把定义时的错误码和业务文案原样交给客户端。
     */
    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(HttpServletRequest request, AppException ex) {

        String info = hasText(ex.getInfo()) ? ex.getInfo() : ResponseCode.UN_ERROR.getInfo();

        // 业务异常通常由参数或规则触发，不打印堆栈，避免高频预期错误淹没系统故障日志。
        log.warn("业务异常，请求: [{} {}]，错误码: {}，异常类型: {}，异常信息: {}",
                request.getMethod(), request.getRequestURI(), ex.getCode(), ex.getClass().getName(), redact(info));

        return Response.error(ex.getCode(), info);
    }

    /**
     * 捕获未预期的系统异常。
     *
     * <p>客户端只能得到稳定的通用文案，完整的异常链和堆栈帧仅写入服务端日志。日志副本会先脱敏，
     * 防止供应商异常文本意外携带 apiKey、Authorization 或 MCP header。
     */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleUnexpectedException(HttpServletRequest request, Exception ex) {

        log.error("系统异常，请求: [{} {}]，异常类型: {}",
                request.getMethod(), request.getRequestURI(), ex.getClass().getName());
        log.error("系统异常堆栈：{}", formatSanitizedStackTrace(ex));

        return Response.error("操作失败, 请稍后再试");
    }

    /**
     * 输出原始异常类型、原因链和堆栈帧，仅替换消息中的敏感键值；不能直接记录原异常，
     * 因为第三方 SDK 的异常消息不受本服务控制。手动格式化还能避免包装异常掩盖实际异常类型。
     */
    private String formatSanitizedStackTrace(Throwable exception) {
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

        // 防御性标记：少数自定义异常可能构造出循环 cause，不能让异常处理器自身死循环。
        if (current != null) {
            trace.append("Caused by: [检测到循环异常引用，后续原因链已省略]");
        }
        return trace.toString();
    }

    private String redact(String message) {
        return message == null ? null : SENSITIVE_VALUE.matcher(message).replaceAll(REDACTED_VALUE);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
