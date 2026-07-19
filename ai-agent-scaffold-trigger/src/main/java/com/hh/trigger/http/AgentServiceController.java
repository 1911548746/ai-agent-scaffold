package com.hh.trigger.http;

import com.google.adk.events.Event;
import com.hh.api.IAgentService;
import com.hh.api.dto.*;
import com.hh.api.response.Response;
import com.hh.domain.agent.model.valobj.AgentInfo;
import com.hh.domain.agent.service.IChatService;
import com.hh.types.support.SensitiveDataRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class AgentServiceController implements IAgentService {

    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final IChatService chatService;

    public AgentServiceController(IChatService chatService) {
        this.chatService = chatService;
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

        List<AgentInfo> agentInfoList = chatService.queryAgentList();

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
     *     "agentId": "100000",
     *     "userId": "100000"
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
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {

        log.info("创建会话 agentId:{} userId:{}",
                requestDTO.agentId(), requestDTO.userId());

        String sessionId = chatService.createSession(requestDTO.agentId(), requestDTO.userId());

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
     *     "userId": "100000",
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
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {

        log.info("智能体对话 agentId:{} userId:{} sessionId:{}",
                requestDTO.agentId(), requestDTO.userId(), requestDTO.sessionId());

        List<String> messages = chatService.handleMessage(
                requestDTO.agentId(),
                requestDTO.userId(),
                requestDTO.sessionId(),
                requestDTO.message()
        );

        ChatResponseDTO chatResponseDTO = new ChatResponseDTO(String.join("\n", messages));

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
     *     "userId": "100000",
     *     "sessionId": "eecf9e2e-6913-4898-b20b-12a8d00cf848",
     *     "message": "你好"
     *   }'
     * </pre>
     *
     * <p>成功响应示例（SSE 流式输出，每个事件独立推送）：
     * <pre>
     * event:message
     * data:你好
     * retry:3000
     *
     * event:message
     * data:！我是
     * retry:3000
     *
     * event:message
     * data: **Hello
     * retry:3000
     *
     * event:message
     * data:Agent**
     * retry:3000
     * </pre>
     *
     * <p>前端使用 EventSource 接收示例：
     * <pre>{@code
     * const eventSource = new EventSource('/api/v1/chat-stream');
     *
     * eventSource.addEventListener('message', function(event) {
     *     console.log('收到消息:', event.data);
     *     // 逐字拼接显示 AI 回复
     * });
     *
     * eventSource.addEventListener('error', function(event) {
     *     console.error('发生错误:', event.data);
     * });
     * }</pre>
     *
     * <p>响应说明：
     * <ul>
     *   <li><b>event:message</b> - 标准消息事件</li>
     *   <li><b>data:{content}</b> - 消息内容片段（需前端拼接）</li>
     *   <li><b>event:error</b> - 固定 JSON 错误对象，包含公开的 {@code code} 和 {@code info}</li>
     *   <li><b>retry:3000</b> - 断线后 3 秒自动重连</li>
     * </ul>
     */
    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {

        log.info("智能体对话流式输出 agentId:{} userId:{} sessionId:{}",
                requestDTO.agentId(), requestDTO.userId(), requestDTO.sessionId());

        SseEmitter emitter = createEmitter();

        // 订阅消息流
        chatService.handleMessageStream(
                requestDTO.agentId(),
                requestDTO.userId(),
                requestDTO.sessionId(),
                requestDTO.message()
        ).subscribe(
                event -> {
                    try {

                        // 忽略 FunctionCall 和 FunctionResponse
                        if (!event.finalResponse())
                            return;

                        // 发送标准 SSE 格式数据
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(event.stringifyContent())
                                .reconnectTime(3000));
                    } catch (IOException e) {
                        // 写入失败通常表示客户端已断开；记录脱敏日志后关闭流，不能再将异常交给 MVC 输出。
                        log.warn("智能体对话流式输出失败，异常类型: {}，异常详情: {}",
                                e.getClass().getName(), SensitiveDataRedactor.formatStackTrace(e));
                        emitter.complete();
                    }
                },
                error -> {
                    completeWithSafeError(emitter, error);
                },
                () -> {
                    log.info("流式消息处理完成");
                    emitter.complete();
                }
        );

        // 设置超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时");
            emitter.complete();
        });

        // 设置完成回调
        emitter.onCompletion(() -> {
            log.info("SSE 连接已关闭");
        });

        return emitter;
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
        log.error("智能体对话流式输出出错，异常类型: {}，异常详情: {}",
                error.getClass().getName(), SensitiveDataRedactor.formatStackTrace(error));
        try {
            sendEvent(emitter, "error", StreamErrorResponseDTO.internalError());
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

}
