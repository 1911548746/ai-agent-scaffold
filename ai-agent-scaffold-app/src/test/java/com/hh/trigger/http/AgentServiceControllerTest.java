package com.hh.trigger.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.adk.events.Event;
import com.hh.api.dto.ChatRequestDTO;
import com.hh.api.dto.StreamErrorResponseDTO;
import com.hh.domain.agent.service.IChatService;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    private final Logger logger = (Logger) LoggerFactory.getLogger(AgentServiceController.class);
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
    void streamErrorSendsStablePayloadAndRedactsProviderDetails() {
        String sensitiveValue = "must-not-reach-client-or-log";
        IChatService chatService = mock(IChatService.class);
        when(chatService.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flowable.error(new IllegalStateException("provider rejected apiKey=" + sensitiveValue)));
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatService);

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
    void streamEmitterLifecycleDisposesUpstreamSubscription() {
        assertLifecycleCancelsUpstream(TrackingSseEmitter::triggerTimeout);
        assertLifecycleCancelsUpstream(TrackingSseEmitter::triggerCompletion);
        assertLifecycleCancelsUpstream(emitter -> emitter.triggerError(new IOException("client disconnected")));
    }

    /**
     * 三种 Servlet 生命周期信号都必须 dispose 同一个订阅，避免上游模型在客户端离开后继续生成。
     */
    private void assertLifecycleCancelsUpstream(Consumer<TrackingSseEmitter> lifecycleEvent) {
        AtomicInteger cancellations = new AtomicInteger();
        IChatService chatService = mock(IChatService.class);
        when(chatService.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flowable.<Event>never().doOnCancel(cancellations::incrementAndGet));
        CapturingAgentServiceController controller = new CapturingAgentServiceController(chatService);

        controller.chatStream(new ChatRequestDTO("100000", "user-1", "session-1", "hello"));
        lifecycleEvent.accept(controller.emitter);

        assertThat(cancellations).hasValue(1);
    }

    /**
     * 捕获 Controller 准备发送的事件，直接验证业务边界，不依赖 Servlet 的 SSE 缓冲实现。
     */
    private static final class CapturingAgentServiceController extends AgentServiceController {

        private final TrackingSseEmitter emitter = new TrackingSseEmitter();
        private String eventName;
        private Object eventData;

        private CapturingAgentServiceController(IChatService chatService) {
            super(chatService);
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
