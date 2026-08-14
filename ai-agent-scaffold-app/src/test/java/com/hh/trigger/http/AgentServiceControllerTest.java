package com.hh.trigger.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hh.api.agent.dto.ChatRequestDTO;
import com.hh.api.agent.dto.StreamErrorResponseDTO;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.trigger.http.agent.AgentServiceController;
import com.hh.trigger.http.auth.AuthenticatedPrincipalContext;
import com.hh.trigger.http.agent.support.StreamErrorResponseMapper;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import com.hh.types.enums.ResponseCode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 SSE 错误边界，不启动 Web 容器、模型或数据库。
 */
class AgentServiceControllerTest {

    // SSE 错误诊断已统一收敛到映射器，测试也应监听实际的日志责任边界。
    private final Logger logger = (Logger) LoggerFactory.getLogger(StreamErrorResponseMapper.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        // 此测试直接实例化 Controller，不经过 Spring AOP；显式建立测试认证主体。
        AuthenticatedPrincipalContext.set(new AuthenticatedPrincipal("user-1", "test-user"));
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        AuthenticatedPrincipalContext.clear();
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void streamErrorSendsStablePayloadAndRedactsProviderDetails() {
        String sensitiveValue = "must-not-reach-client-or-log";
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(observer -> {
                    observer.onError(new IllegalStateException("provider rejected apiKey=" + sensitiveValue));
                    return () -> { };
                });
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventName).isEqualTo("error");
        assertThat(controller.eventData).isInstanceOf(StreamErrorResponseDTO.class);
        StreamErrorResponseDTO response = (StreamErrorResponseDTO) controller.eventData;
        assertThat(response.code()).isEqualTo("0001");
        assertThat(response.info()).isEqualTo("操作失败, 请稍后再试");
        assertThat(response.toString()).doesNotContain(sensitiveValue);
        assertThat(controller.emitter.completed).isTrue();
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(sensitiveValue));
    }

    @Test
    void classifiedModelFailureUsesStableSseCodeAndCompactLog() {
        String sensitiveValue = "must-not-reach-client-or-log";
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(observer -> {
                    observer.onError(new ModelInvocationException(
                            ModelInvocationException.FailureType.TIMEOUT,
                            new IllegalStateException("apiKey=" + sensitiveValue)));
                    return () -> { };
                });
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventData).isInstanceOf(StreamErrorResponseDTO.class);
        StreamErrorResponseDTO response = (StreamErrorResponseDTO) controller.eventData;
        assertThat(response.code()).isEqualTo(ResponseCode.MODEL_TIMEOUT.getCode());
        assertThat(response.info()).isEqualTo(ResponseCode.MODEL_TIMEOUT.getInfo());
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains(ModelInvocationException.FailureType.TIMEOUT.name())
                        .contains(IllegalStateException.class.getName())
                        .doesNotContain(sensitiveValue)
                        .doesNotContain("\tat "));
    }

    @Test
    void setupBusinessFailurePreservesBuiltInCodeInSseErrorEvent() {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AppException(ResponseCode.AGENT_NOT_FOUND));
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventName).isEqualTo("error");
        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO
                .knownError(ResponseCode.AGENT_NOT_FOUND));
        assertThat(controller.emitter.completed).isTrue();
    }

    @Test
    void asyncWrappedBusinessFailurePreservesBuiltInCodeInSseErrorEvent() {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(observer -> {
                    // 模拟异步框架在回调边界额外包装业务异常的情形。
                    observer.onError(new IllegalStateException(
                            "async wrapper", new AppException(ResponseCode.AGENT_NOT_FOUND)));
                    return () -> { };
                });
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO
                .knownError(ResponseCode.AGENT_NOT_FOUND));
    }

    @Test
    void nestedModelFailureKeepsSpecificModelCodeWhenOuterErrorIsGeneric() {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(observer -> {
                    observer.onError(new AppException(ResponseCode.UN_ERROR.getCode(),
                            new ModelInvocationException(
                                    ModelInvocationException.FailureType.TIMEOUT,
                                    new IllegalStateException("provider detail"))));
                    return () -> { };
                });
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO
                .knownError(ResponseCode.MODEL_TIMEOUT));
    }

    @Test
    void streamEmitterLifecycleDisposesUpstreamSubscription() {
        assertLifecycleCancelsUpstream(TrackingSseEmitter::triggerTimeout);
        assertLifecycleCancelsUpstream(TrackingSseEmitter::triggerCompletion);
        assertLifecycleCancelsUpstream(emitter -> emitter.triggerError(new IOException("client disconnected")));
    }

    @Test
    void successiveEmitterTerminalSignalsCancelUpstreamOnlyOnce() {
        AtomicInteger cancellations = new AtomicInteger();
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(neverEndingStream(cancellations));
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));
        controller.emitter.triggerTimeout();
        controller.emitter.triggerCompletion();
        controller.emitter.triggerError(new IOException("client disconnected"));

        // Servlet 容器会组合触发多个生命周期回调，不能对同一个模型流重复 dispose。
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void streamEventIsMappedToExistingMessageSseContract() {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(observer -> {
                    observer.onEvent(new ChatEvent("回复片段"));
                    observer.onComplete();
                    return () -> { };
                });
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));

        assertThat(controller.eventName).isEqualTo("message");
        assertThat(controller.eventData).isEqualTo("回复片段");
        assertThat(controller.emitter.completed).isTrue();
    }

    /**
     * 三种 Servlet 生命周期信号都必须 dispose 同一个订阅，避免上游模型在客户端离开后继续生成。
     */
    private void assertLifecycleCancelsUpstream(Consumer<TrackingSseEmitter> lifecycleEvent) {
        AtomicInteger cancellations = new AtomicInteger();
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        when(chatUseCase.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(neverEndingStream(cancellations));
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatUseCase);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));
        lifecycleEvent.accept(controller.emitter);

        assertThat(cancellations).hasValue(1);
    }

    private ChatStream neverEndingStream(AtomicInteger cancellations) {
        return observer -> cancellations::incrementAndGet;
    }

    /**
     * 捕获 Controller 准备发送的事件，直接验证业务边界，不依赖 Servlet 的 SSE 缓冲实现。
     */
    private static final class CapturingAgentServiceController extends AgentServiceController {

        private final TrackingSseEmitter emitter = new TrackingSseEmitter();
        private String eventName;
        private Object eventData;

        private CapturingAgentServiceController(ChatUseCase chatUseCase) {
            super(chatUseCase);
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitter;
        }

        @Override
        protected void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
            this.eventName = eventName;
            this.eventData = data;
        }
    }

    /**
     * 只记录关闭状态；真实网络连接不属于本单元测试范围。
     */
    private static final class TrackingSseEmitter extends SseEmitter {

        private boolean completed;
        private Runnable timeoutCallback;
        private Runnable completionCallback;
        private Consumer<Throwable> errorCallback;

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorCallback = callback;
        }

        @Override
        public void complete() {
            completed = true;
        }

        private void triggerTimeout() {
            timeoutCallback.run();
        }

        private void triggerCompletion() {
            completionCallback.run();
        }

        private void triggerError(Throwable error) {
            errorCallback.accept(error);
        }
    }

}
