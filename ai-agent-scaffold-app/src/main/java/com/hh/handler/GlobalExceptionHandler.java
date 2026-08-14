package com.hh.handler;

import com.hh.api.agent.response.Response;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.types.exception.AppException;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AuthenticationException;
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
     * 模型重试耗尽后返回稳定错误码，并只记录一次精简诊断信息。
     *
     * <p>中间重试由 LangChain4j 负责；这里不重复重试，也不记录可能包含请求或供应商响应的
     * 原始 message。未知系统异常仍交由兜底处理器记录脱敏完整堆栈。</p>
     */
    @ExceptionHandler(ModelInvocationException.class)
    public ResponseEntity<Response<Void>> handleModelInvocationException(
            HttpServletRequest request, ModelInvocationException ex) {

        log.error("模型调用最终失败，请求: [{} {}]，失败类型: {}，根因类型: {}",
                request.getMethod(), request.getRequestURI(), ex.failureType(), ex.rootCauseType());

        return ResponseEntity.status(modelFailureStatus(ex.failureType()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.error(ex.getCode(), ex.getInfo()));
    }

    /** fixedSize 的前置压缩失败时，本轮主模型尚未开始，客户端可安全重试。 */
    @ExceptionHandler(ContextCompactionException.class)
    public ResponseEntity<Response<Void>> handleContextCompactionException(
            HttpServletRequest request, ContextCompactionException ex) {
        log.error("上下文压缩失败，请求: [{} {}]，根因类型: {}",
                request.getMethod(), request.getRequestURI(), ex.rootCauseType());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.error(ex.getCode(), ex.getInfo()));
    }

    /**
     * 统一处理 JSON 请求体的 Bean Validation 失败。
     *
     * <p>校验注解中的 message 属于项目定义的接口提示，直接作为 info 返回；被拒绝的原始值可能
     * 包含用户消息或其他敏感内容，因此日志和响应都不记录 rejectedValue。</p>
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

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(this::hasText)
                .findFirst()
                .orElse("请求参数不合法");
        return badRequest(message);
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

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Response<Void>> handleAuthenticationException(
            HttpServletRequest request, AuthenticationException ex) {
        log.warn("认证失败，请求: [{} {}]", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.error(ResponseCode.AUTHENTICATION_REQUIRED.getCode(),
                        ResponseCode.AUTHENTICATION_REQUIRED.getInfo()));
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

    /** 未知格式错误仍统一使用通用提示，不能透传 Jackson 或容器的原始异常文本。 */
    private ResponseEntity<Response<Void>> badRequest() {
        return badRequest("请求参数不合法");
    }

    /**
     * 参数错误统一使用 HTTP 400 和稳定业务码；可公开的具体校验提示由调用方预先筛选。
     */
    private ResponseEntity<Response<Void>> badRequest(String info) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                // 未指定 Accept 时 classpath 中的 XML converter 也可能被选中；API 错误契约固定为 JSON。
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.error(ResponseCode.ILLEGAL_PARAMETER.getCode(), info));
    }

    /**
     * HTTP 状态只表达当前请求结果；供应商限流不能错误映射为调用本接口的用户被限流。
     */
    private HttpStatus modelFailureStatus(ModelInvocationException.FailureType failureType) {
        return switch (failureType) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RATE_LIMITED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFIGURATION_ERROR, REQUEST_REJECTED -> HttpStatus.BAD_GATEWAY;
        };
    }

}
