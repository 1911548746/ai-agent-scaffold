package com.hh.trigger.http.agent;

import com.hh.api.agent.IAgentService;
import com.hh.api.agent.dto.*;
import com.hh.api.agent.response.Response;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.trigger.http.agent.parser.MultimodalAttachmentParser;
import com.hh.trigger.http.agent.parser.ParsedAttachment;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.trigger.http.agent.support.StreamErrorResponseMapper;
import com.hh.trigger.http.auth.AuthenticatedPrincipalContext;
import com.hh.types.support.SensitiveDataRedactor;
import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class AgentServiceController implements IAgentService {

    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final ChatUseCase chatUseCase;
    private final MultimodalAttachmentParser multimodalAttachmentParser;

    public AgentServiceController(ChatUseCase chatUseCase, MultimodalAttachmentParser multimodalAttachmentParser) {
        this.chatUseCase = chatUseCase;
        this.multimodalAttachmentParser = multimodalAttachmentParser;
    }

    /**
     * 查询智能体配置列表
     *
     * <p>HTTP：GET /api/v1/query-agent-list
     *
     * <p>curl：
     * <pre>
     * curl -X GET 'https://localhost:8080/api/v1/query-agent-list'
     * </pre>
     *
     * <p>成功响应示例：
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": [
     *     {
     *       "agentId": "100000",
     *       "agentName": "测试智能体00",
     *       "agentDesc": "一个测试的智能体"
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/query-agent-list")
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {

        log.info("查询智能体配置列表");

        List<AgentDescriptor> agentInfoList = chatUseCase.queryAgentList();

        // 转换数据对象
        List<AiAgentConfigResponseDTO> responseDTOS = agentInfoList.stream()
                .map(agentInfo -> new AiAgentConfigResponseDTO(
                        agentInfo.getAgentId(),
                        agentInfo.getAgentName(),
                        agentInfo.getAgentDesc()
                )).toList();

        return Response.success(responseDTOS);
    }

    /**
     * 创建会话
     *
     * <p>HTTP：POST /api/v1/create-session
     *
     * <p>curl：
     * <pre>
     * curl -X POST 'https://localhost:8080/api/v1/create-session' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000"
     *   }'
     * </pre>
     *
     * <p>成功响应示例：
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "sessionId": "eecf9e2e-6913-4898-b20b-12a8d00cf848"
     *   }
     * }
     * </pre>
     */
    @PostMapping("/create-session")
    @Override
    public Response<CreateSessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequestDTO requestDTO) {

        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("创建会话 agentId:{} userId:{}", requestDTO.agentId(), userId);

        String sessionId = chatUseCase.createSession(requestDTO.agentId(), userId);

        CreateSessionResponseDTO createSessionResponseDTO = new CreateSessionResponseDTO(sessionId);

        return Response.success(createSessionResponseDTO);
    }

    /**
     * 智能体对话
     *
     * <p>HTTP：POST /api/v1/chat
     *
     * <p>curl：
     * <pre>
     * curl -X POST 'https://localhost:8080/api/v1/chat' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000",
     *     "sessionId": "eecf9e2e-6913-4898-b20b-12a8d00cf848",
     *     "message": "你好"
     *   }'
     * </pre>
     *
     * <p>成功响应示例：
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "content": "你好！很高兴见到你！😊 有什么我可以帮你的吗？比如查询天气或者其他问题？"
     *   }
     * }
     * </pre>
     */
    @PostMapping("/chat")
    @Override
    public Response<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO requestDTO) {

        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("智能体对话 agentId:{} userId:{} sessionId:{}",
                requestDTO.agentId(), userId, requestDTO.sessionId());

        ChatResult result = chatUseCase.handleMessage(
                requestDTO.agentId(),
                userId,
                requestDTO.sessionId(),
                requestDTO.message()
        );

        ChatResponseDTO chatResponseDTO = new ChatResponseDTO(
                result.joinContents(),
                result.pendingConfirmations().stream()
                        .map(this::toConfirmationResponse)
                        .toList());

        return Response.success(chatResponseDTO);
    }

    /**
     * 多模态同步对话。
     *
     * <p>HTTP：POST /api/v1/chat-multimodal，使用 multipart/form-data，files 可重复上传多个附件。
     * 首版仅支持图片和 UTF-8 txt 文件；原始附件只在本次模型调用期间保留。</p>
     */
    @PostMapping(value = "/chat-multimodal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<ChatResponseDTO> chatMultimodal(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(required = false) String message,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {

        if (agentId == null || agentId.isBlank() || agentId.length() > RequestPayloadLimits.MAX_IDENTIFIER_LENGTH
                || sessionId == null || sessionId.isBlank()
                || sessionId.length() > RequestPayloadLimits.MAX_SESSION_ID_LENGTH
                || message != null && message.length() > RequestPayloadLimits.MAX_TEXT_LENGTH) {
            throw new com.hh.types.exception.AppException(com.hh.types.enums.ResponseCode.ILLEGAL_PARAMETER);
        }

        List<ParsedAttachment> parsedAttachments = multimodalAttachmentParser.parse(files);
        if ((message == null || message.isBlank()) && parsedAttachments.isEmpty()) {
            throw new com.hh.types.exception.AppException(
                    com.hh.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), "message或files至少提供一项");
        }

        List<ChatCommandEntity.Content.Text> texts = message == null || message.isBlank()
                ? List.of()
                : List.of(new ChatCommandEntity.Content.Text(message));

        List<ChatCommandEntity.Content.InlineData> inlineDatas = parsedAttachments.stream()
                .map(file -> new ChatCommandEntity.Content.InlineData(file.bytes(), file.mimeType()))
                .toList();

        ChatCommandEntity command = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(AuthenticatedPrincipalContext.requireCurrent().userId())
                .sessionId(sessionId)
                .texts(texts)
                .inlineDatas(inlineDatas)
                .build();

        ChatResult result = chatUseCase.handleMessage(command);

        ChatResponseDTO chatResponseDTO = new ChatResponseDTO(
                result.joinContents(),
                result.pendingConfirmations().stream().map(this::toConfirmationResponse).toList());

        return Response.success(chatResponseDTO);
    }

    /**
     * 智能体对话流式输出
     *
     * <p>HTTP：POST /api/v1/chat-stream
     *
     * <p>curl：
     * <pre>
     * curl -X POST 'https://localhost:8080/api/v1/chat-stream' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000",
     *     "sessionId": "eecf9e2e-6913-4898-b20b-12a8d00cf848",
     *     "message": "你好"
     *   }'
     * </pre>
     *
     * <p>成功响应示例（SSE 流式输出，每个事件独立推送）：
     * <pre>
     * event:message
     * data:你好
     *
     * event:message
     * data:！我是
     *
     * event:message
     * data: **Hello
     *
     * event:message
     * data:Agent**
     * </pre>
     *
     * <p>本接口是 POST，原生 {@code EventSource} 只能发起 GET，不能直接调用。前端应使用
     * {@code fetch} 读取响应流，例如：
     * <pre>{@code
     * const abortController = new AbortController();
     * const response = await fetch('/api/v1/chat-stream', {
     *   method: 'POST',
     *   headers: {'Content-Type': 'application/json'},
     *   body: JSON.stringify({agentId, sessionId, message}),
     *   signal: abortController.signal
     * });
     * if (!response.ok || !response.body) throw new Error('SSE request failed');
     *
     * const reader = response.body.getReader();
     * const decoder = new TextDecoder();
     * let pending = '';
     * while (true) {
     *   const {done, value} = await reader.read();
     *   pending += decoder.decode(value || new Uint8Array(), {stream: !done});
     *   for (let boundary; (boundary = pending.indexOf('\n\n')) >= 0;) {
     *     const frame = pending.slice(0, boundary).replace(/\r/g, '');
     *     pending = pending.slice(boundary + 2);
     *     const event = frame.match(/^event:(.+)$/m)?.[1]?.trim();
     *     const data = frame.match(/^data:(.*)$/m)?.[1]?.trim() || '';
     *     if (event === 'message') appendAssistantText(data);
     *     if (event === 'error') showError(JSON.parse(data));
     *   }
     *   if (done) break;
     * }
     * // 用户停止生成时调用 abortController.abort()，服务端会取消上游模型流。
     * }</pre>
     *
     * <p>响应说明：
     * <ul>
     *   <li><b>event:message</b> - 标准消息事件</li>
     *   <li><b>event:tool-confirmation</b> - Agent 暂停并等待人工确认的公开请求摘要</li>
     *   <li><b>data:{content}</b> - 消息内容片段（需前端拼接）</li>
     *   <li><b>event:error</b> - 固定 JSON 错误对象，包含公开的 {@code code} 和 {@code info}</li>
     *   <li>重连和取消由 {@code fetch} 调用方控制；当前 POST 接口不提供 EventSource 自动重连。</li>
     * </ul>
     */
    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequestDTO requestDTO) {

        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("智能体对话流式输出 agentId:{} userId:{} sessionId:{}",
                requestDTO.agentId(), userId, requestDTO.sessionId());

        SseEmitter emitter = createEmitter();

        AtomicReference<ChatSubscription> subscriptionReference = new AtomicReference<>();
        AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        AtomicBoolean subscriptionCancelled = new AtomicBoolean(false);
        Runnable cancelUpstream = () -> cancelSubscription(
                subscriptionReference, cancellationRequested, subscriptionCancelled);

        // 先注册回调，避免客户端在订阅建立完成前断开时遗漏取消信号。
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时，取消上游流式调用");
            cancelUpstream.run();
            emitter.complete();
        });
        emitter.onError(error -> {
            log.warn("SSE 异步连接异常，取消上游流式调用，异常类型: {}",
                    error.getClass().getName());
            cancelUpstream.run();
        });
        emitter.onCompletion(() -> {
            // Spring MVC 在正常结束、超时和网络断连时都会进入此回调，统一释放上游订阅。
            cancelUpstream.run();
            log.info("SSE 连接已关闭");
        });

        try {
            // trigger 只把项目 ChatEvent 编码为 SSE，不接触 ADK Event 或 RxJava Disposable。
            ChatSubscription subscription = chatUseCase.handleMessageStream(
                    requestDTO.agentId(),
                    userId,
                    requestDTO.sessionId(),
                    requestDTO.message()
            ).subscribe(new ChatStreamObserver() {
                @Override
                public void onEvent(ChatEvent event) {
                    try {
                        if (event.isToolConfirmation()) {
                            sendEvent(emitter, "tool-confirmation", toConfirmationResponse(event.confirmation()));
                        } else {
                            sendEvent(emitter, "message", event.content());
                        }
                    } catch (IOException e) {
                        // 写入失败通常表示客户端已断开；关闭项目流会由 infrastructure 继续取消 SDK 上游。
                        log.warn("智能体对话流式输出失败，异常类型: {}，异常详情: {}",
                                e.getClass().getName(), SensitiveDataRedactor.formatStackTrace(e));
                        cancelUpstream.run();
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    cancelUpstream.run();
                    completeWithSafeError(emitter, error);
                }

                @Override
                public void onComplete() {
                    log.info("流式消息处理完成");
                    emitter.complete();
                }
            });

            subscriptionReference.set(subscription);
            // 如果回调发生在 subscribe 返回之前，这里补偿执行取消，避免建立竞态泄漏上游任务。
            if (cancellationRequested.get()) {
                cancelSubscription(subscriptionReference, cancellationRequested, subscriptionCancelled);
            }
        } catch (RuntimeException error) {
            // 适配器在建立订阅时直接失败，也必须使用已经开始 SSE 响应的安全错误协议。
            cancelUpstream.run();
            completeWithSafeError(emitter, error);
        }

        return emitter;
    }

    /**
     * 请求取消可能早于 ChatSubscription 创建；先记录状态，再在订阅可用时执行取消。
     */
    private void cancelSubscription(
            AtomicReference<ChatSubscription> subscriptionReference,
            AtomicBoolean cancellationRequested,
            AtomicBoolean subscriptionCancelled) {
        cancellationRequested.set(true);
        ChatSubscription subscription = subscriptionReference.get();
        // timeout、异常和完成回调可能连续到达；同一个上游订阅只能取消一次。
        if (subscription != null && subscriptionCancelled.compareAndSet(false, true)) {
            subscription.cancel();
        }
    }

    /**
     * 单独保留创建入口，测试可替换为内存 emitter，不需要启动 Web 容器。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MILLIS);
    }

    /**
     * 为已开始的 SSE 响应写入固定错误事件并正常结束。
     *
     * <p>不能调用 {@code completeWithError(error)}，否则 Spring MVC 可能根据原始 Throwable
     * 再次走异常响应流程，供应商错误文本仍有机会到达客户端。</p>
     */
    private void completeWithSafeError(SseEmitter emitter, Throwable error) {
        StreamErrorResponseDTO response = StreamErrorResponseMapper.map(error);
        StreamErrorResponseMapper.logFailure("智能体对话流式输出", error);
        try {
            sendEvent(emitter, "error", response);
        } catch (IOException sendError) {
            // 客户端已经断开时无法可靠投递错误事件，只记录已脱敏的服务端诊断信息。
            log.warn("SSE 安全错误事件发送失败，异常类型: {}，异常详情: {}",
                    sendError.getClass().getName(), SensitiveDataRedactor.formatStackTrace(sendError));
        } finally {
            emitter.complete();
        }
    }

    /**
     * 统一事件发送入口，便于离线测试验证事件名称和载荷，而无需依赖 Servlet 输出缓冲区。
     */
    protected void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private ToolConfirmationResponseDTO toConfirmationResponse(ToolConfirmationRequest request) {
        return new ToolConfirmationResponseDTO(
                request.confirmationId(), request.toolName(), request.message());
    }

}
