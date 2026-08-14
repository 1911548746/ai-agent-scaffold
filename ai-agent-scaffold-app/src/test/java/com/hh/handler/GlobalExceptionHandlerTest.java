package com.hh.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hh.api.agent.response.Response;
import com.hh.api.auth.dto.RegisterRequestDTO;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一异常处理的响应边界和日志可诊断性，全程不启动 Spring 容器或访问外部服务。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void appExceptionKeepsBusinessCodeAndInfo() {
        AppException exception = new AppException("AGENT_NOT_FOUND", "Agent does not exist");

        Response<Void> response = handler.handleAppException(request(), exception);

        assertThat(response.getCode()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(response.getInfo()).isEqualTo("Agent does not exist");
        assertThat(response.getData()).isNull();
    }

    @Test
    void unexpectedExceptionLogsSanitizedCauseAndReturnsGenericMessage() {
        String secret = "should-not-appear-in-log";
        RuntimeException cause = new IllegalStateException("Token is invalid; apiKey=" + secret);

        Response<Void> response = handler.handleUnexpectedException(request(), new RuntimeException("model call failed", cause));

        assertThat(response.getCode()).isEqualTo("0001");
        assertThat(response.getInfo()).isEqualTo("操作失败, 请稍后再试");
        assertThat(response.getData()).isNull();
        assertThat(logAppender.list).hasSize(2);

        ILoggingEvent stackTraceEvent = logAppender.list.get(1);
        assertThat(stackTraceEvent.getFormattedMessage()).contains(IllegalStateException.class.getName());
        assertThat(stackTraceEvent.getFormattedMessage()).contains("Token is invalid");
        assertThat(stackTraceEvent.getFormattedMessage()).doesNotContain(secret);
        assertThat(stackTraceEvent.getFormattedMessage()).contains("\tat ");
    }

    @Test
    void modelTimeoutReturnsGatewayTimeoutAndLogsOnlyCompactCauseType() {
        String secret = "must-not-appear";
        ModelInvocationException exception = new ModelInvocationException(
                ModelInvocationException.FailureType.TIMEOUT,
                new IllegalStateException("apiKey=" + secret));

        ResponseEntity<Response<Void>> entity = handler.handleModelInvocationException(request(), exception);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(ResponseCode.MODEL_TIMEOUT.getCode());
        assertThat(entity.getBody().getInfo()).isEqualTo(ResponseCode.MODEL_TIMEOUT.getInfo());
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains(ModelInvocationException.FailureType.TIMEOUT.name())
                .contains(IllegalStateException.class.getName())
                .doesNotContain(secret)
                .doesNotContain("\tat ");
    }

    @Test
    void preflightCompactionFailureReturnsRetryableServiceUnavailableResponse() {
        ResponseEntity<Response<Void>> entity = handler.handleContextCompactionException(
                request(), new ContextCompactionException(new IllegalStateException("provider details")));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(ResponseCode.CONTEXT_COMPACTION_UNAVAILABLE.getCode());
        assertThat(entity.getBody().getInfo()).isEqualTo(ResponseCode.CONTEXT_COMPACTION_UNAVAILABLE.getInfo());
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains(IllegalStateException.class.getName())
                .doesNotContain("provider details");
    }

    @Test
    void validationFailureReturnsDeclaredFieldMessage() throws Exception {
        MethodArgumentNotValidException exception = validationException(
                "password", "123", "password长度必须在8到128个字符之间");

        ResponseEntity<Response<Void>> entity = handler.handleValidationException(request(), exception);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(ResponseCode.ILLEGAL_PARAMETER.getCode());
        assertThat(entity.getBody().getInfo()).isEqualTo("password长度必须在8到128个字符之间");
        assertThat(entity.getBody().getData()).isNull();
        assertThat(entity.getBody().getInfo()).doesNotContain("123");
    }

    /**
     * 请求路径属于排障所需上下文；测试只提供最小 servlet 请求对象，不加载数据库或 Agent 配置。
     */
    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/chat");
    }

    /** 构造与 Spring MVC @Valid 相同的字段校验异常，不启动 Web 容器。 */
    private MethodArgumentNotValidException validationException(
            String field, Object rejectedValue, String defaultMessage) throws Exception {
        RegisterRequestDTO target = new RegisterRequestDTO("alice", "valid-password");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "registerRequestDTO");
        bindingResult.addError(new FieldError(
                "registerRequestDTO", field, rejectedValue, false, null, null, defaultMessage));
        Method method = GlobalExceptionHandlerTest.class
                .getDeclaredMethod("validationTarget", RegisterRequestDTO.class);
        return new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
    }

    @SuppressWarnings("unused")
    private void validationTarget(RegisterRequestDTO requestDTO) {
        // 仅用于构造 Spring MVC 校验异常所需的 MethodParameter。
    }
}
