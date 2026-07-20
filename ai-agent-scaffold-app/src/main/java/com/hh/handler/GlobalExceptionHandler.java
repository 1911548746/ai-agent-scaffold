package com.hh.handler;

import com.hh.api.response.Response;
import com.hh.types.exception.AppException;
import com.hh.types.enums.ResponseCode;
import com.hh.types.support.SensitiveDataRedactor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 统一处理 JSON 请求体的 Bean Validation 失败。
     *
     * <p>字段名足以帮助定位接口契约问题，但被拒绝的原始值可能包含用户消息或其他敏感内容，
     * 因此日志和响应都不记录它。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidationException(
            HttpServletRequest request, MethodArgumentNotValidException ex) {

        String invalidFields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("unknown");
        log.warn("请求参数校验失败，请求: [{} {}]，字段: {}",
                request.getMethod(), request.getRequestURI(), invalidFields);

        return badRequest();
    }

    /**
     * JSON 结构不合法或请求体缺失时，也使用与 Bean Validation 相同的公开响应，
     * 避免不同的 400 分支泄漏 Jackson 的内部解析细节。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadableRequestBody(
            HttpServletRequest request, HttpMessageNotReadableException ex) {

        log.warn("请求体无法解析，请求: [{} {}]，异常类型: {}",
                request.getMethod(), request.getRequestURI(), ex.getClass().getName());

        return badRequest();
    }

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

    /**
     * 参数错误统一使用 HTTP 400 和稳定业务码；响应不携带约束消息，避免把内部字段规则
     * 或用户输入回显给调用方。
     */
    private ResponseEntity<Response<Void>> badRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                // 未指定 Accept 时 classpath 中的 XML converter 也可能被选中；API 错误契约固定为 JSON。
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请求参数不合法"));
    }

}
