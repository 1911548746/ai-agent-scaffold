package com.hh.trigger.http;

import com.hh.api.agent.dto.ChatResponseDTO;
import com.hh.api.agent.dto.StreamErrorResponseDTO;
import com.hh.api.agent.dto.ToolConfirmationDecisionRequestDTO;
import com.hh.api.agent.dto.ToolConfirmationDetailResponseDTO;
import com.hh.api.agent.dto.ToolConfirmationResponseDTO;
import com.hh.api.agent.response.Response;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.model.ToolConfirmationRecord;
import com.hh.application.agent.usecase.ToolConfirmationUseCase;
import com.hh.types.support.SensitiveDataRedactor;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 工具确认查询与批准/拒绝恢复入口。 */
@RestController
@RequestMapping("/api/v1/tool-confirmations")
@Slf4j
public class ToolConfirmationController {

    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final ToolConfirmationUseCase toolConfirmationUseCase;

    public ToolConfirmationController(ToolConfirmationUseCase toolConfirmationUseCase) {
        this.toolConfirmationUseCase = toolConfirmationUseCase;
    }

    /**
     * 查询待确认工具调用。
     *
     * <p>HTTP：GET /api/v1/tool-confirmations/{confirmationId}?userId=100000
     *
     * <p>curl：
     * <pre>
     * curl -X GET 'http://localhost:8080/api/v1/tool-confirmations/confirmation-id?userId=100000'
     * </pre>
     *
     * <p>成功响应示例：
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "confirmationId": "confirmation-id",
     *     "appName": "OrderAgent",
     *     "sessionId": "session-id",
     *     "toolName": "request_refund",
     *     "message": "请确认是否提交退款申请",
     *     "status": "pending",
     *     "expiresAt": "2026-08-12T15:20:00"
     *   }
     * }
     * </pre>
     *
     * <p>当前阶段以请求中的 userId 查询确认单；SEC-04 接入后应改为使用认证上下文。</p>
     */
    @GetMapping("/{confirmationId}")
    public Response<ToolConfirmationDetailResponseDTO> get(
            @PathVariable String confirmationId,
            @RequestParam String userId) {
        // SEC-04 之前临时信任 userId；正式鉴权后由认证主体提供，不允许客户端自由指定。
        return Response.success(toDetail(toolConfirmationUseCase.get(confirmationId, userId)));
    }

    /**
     * 批准或拒绝工具调用，并以普通 HTTP 响应恢复同一 Session。
     *
     * <p>HTTP：POST /api/v1/tool-confirmations/{confirmationId}:resolve
     *
     * <p>curl：
     * <pre>
     * curl -X POST 'http://localhost:8080/api/v1/tool-confirmations/confirmation-id:resolve' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000",
     *     "userId": "100000",
     *     "confirmed": true
     *   }'
     * </pre>
     *
     * <p>同一确认单只能从 pending 状态领取一次。confirmed 为 true 时 ADK 执行原工具调用；
     * false 时 ADK 接收拒绝结果并继续生成后续回复。恢复产生的 assistant 内容会单独归档，
     * 不与暂停前的内容物理拼接。</p>
     */
    @PostMapping("/{confirmationId}:resolve")
    public Response<ChatResponseDTO> resolve(
            @PathVariable String confirmationId,
            @Valid @RequestBody ToolConfirmationDecisionRequestDTO request) {
        ChatResult result = toolConfirmationUseCase.resolve(
                confirmationId, request.agentId(), request.userId(), request.confirmed());
        return Response.success(toChatResponse(result));
    }

    /**
     * 批准或拒绝工具调用，并以 SSE 输出恢复后的后续内容。
     *
     * <p>HTTP：POST /api/v1/tool-confirmations/{confirmationId}:resolve-stream
     *
     * <p>curl：
     * <pre>
     * curl -N -X POST 'http://localhost:8080/api/v1/tool-confirmations/confirmation-id:resolve-stream' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000",
     *     "userId": "100000",
     *     "confirmed": true
     *   }'
     * </pre>
     *
     * <p>响应说明：
     * <ul>
     *   <li><b>event:message</b> - 恢复任务产生的 assistant 文本片段</li>
     *   <li><b>event:tool-confirmation</b> - 恢复过程再次暂停并需要新的人工确认</li>
     *   <li><b>event:error</b> - 固定错误对象，不包含 ADK 或工具的原始异常信息</li>
     * </ul>
     *
     * <p>该接口会建立新的 SSE/Runner 调用，不会续接首次确认前已经结束的 HTTP 连接。</p>
     */
    @PostMapping(value = "/{confirmationId}:resolve-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resolveStream(
            @PathVariable String confirmationId,
            @Valid @RequestBody ToolConfirmationDecisionRequestDTO request) {
        SseEmitter emitter = createEmitter();
        AtomicReference<ChatSubscription> subscriptionReference = new AtomicReference<>();
        AtomicBoolean terminal = new AtomicBoolean(false);
        Runnable cancel = () -> {
            ChatSubscription subscription = subscriptionReference.get();
            if (subscription != null) {
                subscription.cancel();
            }
        };
        emitter.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                // 超时与客户端断连都要取消恢复 Runner，避免批准后工具仍在后台继续运行。
                cancel.run();
                emitter.complete();
            }
        });
        emitter.onError(error -> cancel.run());
        emitter.onCompletion(cancel);

        try {
            ChatSubscription subscription = toolConfirmationUseCase.resolveStream(
                    confirmationId, request.agentId(), request.userId(), request.confirmed())
                    .subscribe(new ChatStreamObserver() {
                        @Override
                        public void onEvent(ChatEvent event) {
                            if (terminal.get()) {
                                // SseEmitter 已关闭后不能继续发送，避免迟到 Event 触发无意义异常。
                                return;
                            }
                            try {
                                if (event.isToolConfirmation()) {
                                    // 恢复过程可能产生下一张确认单，继续沿用项目自己的 SSE DTO。
                                    send(emitter, "tool-confirmation", toConfirmation(event.confirmation()));
                                } else {
                                    send(emitter, "message", event.content());
                                }
                            } catch (IOException error) {
                                log.warn("确认恢复 SSE 写入失败，异常类型: {}", error.getClass().getName());
                                if (terminal.compareAndSet(false, true)) {
                                    cancel.run();
                                    emitter.complete();
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (terminal.compareAndSet(false, true)) {
                                // SSE 已开始响应时只能发送稳定错误事件，不能回传 SDK 原始异常。
                                log.error("确认恢复执行失败，异常类型: {}，异常详情: {}",
                                        error.getClass().getName(), SensitiveDataRedactor.formatStackTrace(error));
                                try {
                                    send(emitter, "error", StreamErrorResponseDTO.internalError());
                                } catch (IOException ignored) {
                                    // 客户端已断开时无法再发送错误事件。
                                } finally {
                                    emitter.complete();
                                }
                            }
                        }

                        @Override
                        public void onComplete() {
                            if (terminal.compareAndSet(false, true)) {
                                emitter.complete();
                            }
                        }
                    });
            subscriptionReference.set(subscription);
        } catch (RuntimeException error) {
            terminal.set(true);
            // 建流阶段尚未进入异步回调：已知业务异常保留原错误码，不能降级为未知错误 0001。
            StreamErrorResponseDTO response = toStreamError(error);
            if (error instanceof AppException appException) {
                log.warn("确认恢复 SSE 建立失败，业务码: {}，异常类型: {}",
                        appException.getCode(), error.getClass().getName());
            } else {
                log.warn("确认恢复 SSE 建立失败，异常类型: {}，异常详情: {}",
                        error.getClass().getName(), SensitiveDataRedactor.formatStackTrace(error));
            }
            try {
                send(emitter, "error", response);
            } catch (IOException ignored) {
                // SSE 尚未建立或客户端已断开时无法发送错误事件。
            } finally {
                emitter.complete();
            }
        }
        return emitter;
    }

    private ToolConfirmationDetailResponseDTO toDetail(ToolConfirmationRecord record) {
        // ADK call id、invocation id 和原始工具参数只服务内部恢复，禁止出现在 HTTP 契约。
        return new ToolConfirmationDetailResponseDTO(
                record.confirmationId(), record.appName(), record.sessionId(), record.toolName(),
                record.message(), record.status().value(),
                record.createdAt() == null ? null : record.createdAt().toString(),
                record.updatedAt() == null ? null : record.updatedAt().toString(),
                record.expiresAt() == null ? null : record.expiresAt().toString());
    }

    private ChatResponseDTO toChatResponse(ChatResult result) {
        List<ToolConfirmationResponseDTO> confirmations = result.pendingConfirmations().stream()
                .map(this::toConfirmation)
                .toList();
        return new ChatResponseDTO(result.joinContents(), confirmations);
    }

    private ToolConfirmationResponseDTO toConfirmation(
            com.hh.application.agent.model.ToolConfirmationRequest request) {
        return new ToolConfirmationResponseDTO(
                request.confirmationId(), request.toolName(), request.message());
    }

    /** 单元测试可替换发送器，验证错误事件而无需启动 Servlet 容器。 */
    protected void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    /** 单元测试可使用内存 emitter；生产环境仍使用标准 Spring SSE emitter。 */
    protected SseEmitter createEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MILLIS);
    }

    /** 只有项目内置错误码允许进入 SSE，避免把客户端可控或 SDK 异常文本作为协议返回。 */
    private StreamErrorResponseDTO toStreamError(Throwable error) {
        if (!(error instanceof AppException appException)) {
            return StreamErrorResponseDTO.internalError();
        }
        for (ResponseCode responseCode : ResponseCode.values()) {
            if (responseCode.getCode().equals(appException.getCode())) {
                return StreamErrorResponseDTO.knownError(responseCode);
            }
        }
        return StreamErrorResponseDTO.internalError();
    }
}
