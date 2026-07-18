package com.hh.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hh.api.response.Response;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

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

    /**
     * 请求路径属于排障所需上下文；测试只提供最小 servlet 请求对象，不加载数据库或 Agent 配置。
     */
    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/chat");
    }
}
