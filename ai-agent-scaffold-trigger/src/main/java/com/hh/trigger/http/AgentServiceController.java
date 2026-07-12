package com.hh.trigger.http;

import com.google.adk.events.Event;
import com.hh.api.IAgentService;
import com.hh.api.dto.*;
import com.hh.api.response.Response;
import com.hh.domain.agent.model.valobj.AgentInfo;
import com.hh.domain.agent.service.IChatService;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

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
     *   <li><b>retry:3000</b> - 断线后 3 秒自动重连</li>
     * </ul>
     */
    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public SseEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {

        log.info("智能体对话流式输出 agentId:{} userId:{} sessionId:{}",
                requestDTO.agentId(), requestDTO.userId(), requestDTO.sessionId());

        // 创建 SseEmitter，超时时间 5 分钟
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

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
                        log.error("智能体对话流式输出失败", e);
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("智能体对话流式输出出错", error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("处理失败: " + error.getMessage()));
                    } catch (IOException e) {
                        log.error("智能体对话流式输出失败", e);
                    }
                    emitter.completeWithError(error);
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

}
