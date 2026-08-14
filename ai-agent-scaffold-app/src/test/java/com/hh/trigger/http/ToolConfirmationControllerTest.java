package com.hh.trigger.http;

import com.hh.api.agent.dto.StreamErrorResponseDTO;
import com.hh.api.agent.dto.ToolConfirmationDecisionRequestDTO;
import com.hh.application.agent.usecase.ToolConfirmationUseCase;
import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.trigger.http.agent.ToolConfirmationController;
import com.hh.trigger.http.auth.AuthenticatedPrincipalContext;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HITL SSE 建流阶段必须保留业务错误码，未知异常才可降级为 0001。 */
class ToolConfirmationControllerTest {

    @BeforeEach
    void setPrincipal() {
        // 直接调用 Controller 时模拟切面已经完成 JWT 验证。
        AuthenticatedPrincipalContext.set(new AuthenticatedPrincipal("user-1", "test-user"));
    }

    @AfterEach
    void clearPrincipal() {
        AuthenticatedPrincipalContext.clear();
    }

    @Test
    void duplicateDecisionPreservesBusinessCodeInSseErrorEvent() {
        ToolConfirmationUseCase useCase = mock(ToolConfirmationUseCase.class);
        when(useCase.resolveStream(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new AppException(ResponseCode.TOOL_CONFIRMATION_NOT_PENDING));
        CapturingController controller = new CapturingController(useCase);

        controller.resolveStream("confirmation-1",
                new ToolConfirmationDecisionRequestDTO("agent-a", "user-1", true));

        assertThat(controller.eventName).isEqualTo("error");
        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO
                .knownError(ResponseCode.TOOL_CONFIRMATION_NOT_PENDING));
        assertThat(controller.emitter.completed).isTrue();
    }

    @Test
    void unknownSetupFailureStillUsesGenericSseError() {
        ToolConfirmationUseCase useCase = mock(ToolConfirmationUseCase.class);
        when(useCase.resolveStream(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("provider detail"));
        CapturingController controller = new CapturingController(useCase);

        controller.resolveStream("confirmation-1",
                new ToolConfirmationDecisionRequestDTO("agent-a", "user-1", true));

        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO.internalError());
    }

    @Test
    void asyncBusinessFailurePreservesBuiltInCodeInSseErrorEvent() {
        ToolConfirmationUseCase useCase = mock(ToolConfirmationUseCase.class);
        when(useCase.resolveStream(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(observer -> {
                    // 模拟 Runner 在订阅完成后才报告重复决策的情况。
                    observer.onError(new IllegalStateException(
                            "async wrapper", new AppException(ResponseCode.TOOL_CONFIRMATION_NOT_PENDING)));
                    return () -> { };
                });
        CapturingController controller = new CapturingController(useCase);

        controller.resolveStream("confirmation-1",
                new ToolConfirmationDecisionRequestDTO("agent-a", "user-1", true));

        assertThat(controller.eventName).isEqualTo("error");
        assertThat(controller.eventData).isEqualTo(StreamErrorResponseDTO
                .knownError(ResponseCode.TOOL_CONFIRMATION_NOT_PENDING));
        assertThat(controller.emitter.completed).isTrue();
    }

    private static final class CapturingController extends ToolConfirmationController {

        private final TrackingEmitter emitter = new TrackingEmitter();
        private String eventName;
        private Object eventData;

        private CapturingController(ToolConfirmationUseCase toolConfirmationUseCase) {
            super(toolConfirmationUseCase);
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitter;
        }

        @Override
        protected void send(SseEmitter emitter, String eventName, Object data) throws IOException {
            this.eventName = eventName;
            this.eventData = data;
        }
    }

    private static final class TrackingEmitter extends SseEmitter {

        private boolean completed;

        @Override
        public void complete() {
            completed = true;
        }
    }
}
