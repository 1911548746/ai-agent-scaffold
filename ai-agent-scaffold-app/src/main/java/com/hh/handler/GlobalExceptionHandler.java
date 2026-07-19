package com.hh.handler;

import com.hh.api.response.Response;
import com.hh.types.exception.AppException;
import com.hh.types.enums.ResponseCode;
import com.hh.types.support.SensitiveDataRedactor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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
        log.error("系统异常堆栈：{}", SensitiveDataRedactor.formatStackTrace(ex));

        return Response.error("操作失败, 请稍后再试");
    }

    private String redact(String message) {
        return SensitiveDataRedactor.redact(message);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
