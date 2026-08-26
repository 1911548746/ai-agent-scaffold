package com.hh.trigger.http.agent;

import com.hh.api.agent.IConversationService;
import com.hh.api.agent.dto.ConversationDeleteRequestDTO;
import com.hh.api.agent.dto.ConversationDetailResponseDTO;
import com.hh.api.agent.dto.ConversationListResponseDTO;
import com.hh.api.agent.dto.MessageItemDTO;
import com.hh.api.agent.response.Response;
import com.hh.application.agent.model.ConversationDeletionCommand;
import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.trigger.http.auth.AuthenticatedPrincipalContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
@Slf4j
public class ConversationController implements IConversationService {

    private final ConversationArchiveUseCase conversationArchiveUseCase;
    public ConversationController(ConversationArchiveUseCase conversationArchiveUseCase) {
        this.conversationArchiveUseCase = conversationArchiveUseCase;
    }

    /**
     * 查询会话列表
     *
     * <p>HTTP：GET /api/v1/conversations/list</p>
     *
     * <p>当前登录用户由认证上下文确定，接口不会接受客户端传入的 userId。</p>
     *
     * <p>curl：</p>
     * <pre>
     * curl -X GET 'http://localhost:8080/api/v1/conversations/list' \
     *   -H 'Authorization: Bearer &lt;access-token&gt;'
     * </pre>
     *
     * <p>成功响应示例：</p>
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": [
     *     {
     *       "conversationId": "6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42",
     *       "appName": "HelloAgent",
     *       "sessionId": "00d99331-9e79-4ecd-80dc-807411087987",
     *       "title": "conversation 00d99331-9e79-4ecd-80dc-807411087987",
     *       "createdAt": "2026-07-17T03:46:01",
     *       "updatedAt": "2026-07-17T03:47:40"
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/list")
    @Override
    public Response<List<ConversationListResponseDTO>> listConversations() {
        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("查询会话列表 userId:{}", userId);

        List<ConversationVO> conversations = conversationArchiveUseCase.listConversations(userId);

        List<ConversationListResponseDTO> dtoList = conversations.stream()
                .map(c -> new ConversationListResponseDTO(
                        c.getConversationId(),
                        c.getAppName(),
                        c.getSessionId(),
                        c.getTitle(),
                        c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                        c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null
                ))
                .toList();

        return Response.success(dtoList);
    }

    /**
     * 查询会话详情（含消息记录）
     *
     * <p>HTTP：GET /api/v1/conversations/detail/{conversationId}</p>
     *
     * <p>当前登录用户由认证上下文确定，只返回该用户有权访问的会话及消息。</p>
     *
     * <p>curl：</p>
     * <pre>
     * curl -X GET 'http://localhost:8080/api/v1/conversations/detail/6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42' \
     *   -H 'Authorization: Bearer &lt;access-token&gt;'
     * </pre>
     *
     * <p>成功响应示例：</p>
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "conversationId": "6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42",
     *     "appName": "HelloAgent",
     *     "sessionId": "00d99331-9e79-4ecd-80dc-807411087987",
     *     "title": "conversation 00d99331-9e79-4ecd-80dc-807411087987",
     *     "createdAt": "2026-07-17T03:46:01",
     *     "updatedAt": "2026-07-17T03:47:40",
     *     "messages": [
     *       {
     *         "role": "user",
     *         "status": "completed",
     *         "contentText": "你好",
     *         "createdAt": "2026-07-17T03:46:01"
     *       },
     *       {
     *         "role": "assistant",
     *         "status": "completed",
     *         "contentText": "你好！我是 HelloAgent，一个简洁的 ADK Java 智能助手 🤖\n\n有什么可以帮你的吗？你可以问我问题，或者让我查询天气等。",
     *         "createdAt": "2026-07-17T03:46:35"
     *       }
     *     ]
     *   }
     * }
     * </pre>
     */
    @GetMapping("/detail/{conversationId}")
    @Override
    public Response<ConversationDetailResponseDTO> getConversationDetail(@PathVariable String conversationId) {
        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("查询会话详情 conversationId:{}", conversationId);

        ConversationVO conversation = conversationArchiveUseCase.getConversation(conversationId, userId);
        List<MessageVO> messages = conversationArchiveUseCase.listMessages(conversationId, userId);

        List<MessageItemDTO> messageDTOs = messages.stream()
                .map(m -> new MessageItemDTO(
                        m.getRole(),
                        m.getStatus(),
                        m.getContentText(),
                        m.getCreatedAt() != null ? m.getCreatedAt().toString() : null
                ))
                .toList();

        ConversationDetailResponseDTO detail = new ConversationDetailResponseDTO(
                conversation.getConversationId(),
                conversation.getAppName(),
                conversation.getSessionId(),
                conversation.getTitle(),
                conversation.getCreatedAt() != null ? conversation.getCreatedAt().toString() : null,
                conversation.getUpdatedAt() != null ? conversation.getUpdatedAt().toString() : null,
                messageDTOs
        );

        return Response.success(detail);
    }

    /**
     * 删除会话
     *
     * <p>HTTP：DELETE /api/v1/conversations/delete</p>
     *
     * <p>请求体需要同时提供 {@code agentId}、{@code sessionId} 和 {@code conversationId}；
     * 用户身份由认证上下文确定，服务端会校验会话与会话记录的归属关系。</p>
     *
     * <p>curl：</p>
     * <pre>
     * curl -X DELETE 'http://localhost:8080/api/v1/conversations/delete' \
     *   -H 'Authorization: Bearer &lt;access-token&gt;' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "agentId": "100000",
     *     "sessionId": "eecf9e2e-6913-4898-b20b-12a8d00cf848",
     *     "conversationId": "6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42"
     *   }'
     * </pre>
     *
     * <p>成功响应示例：</p>
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": null
     * }
     * </pre>
     */
    @DeleteMapping("/delete")
    @Override
    public Response<Void> deleteConversation(@Valid @RequestBody ConversationDeleteRequestDTO requestDTO) {
        String userId = AuthenticatedPrincipalContext.requireCurrent().userId();
        log.info("删除会话 conversationId:{}", requestDTO.conversationId());

        // 删除编排必须只有一个用例入口，避免 Controller 先后调用两个独立删除造成部分成功。
        conversationArchiveUseCase.deleteConversation(new ConversationDeletionCommand(
                requestDTO.conversationId(),
                requestDTO.agentId(),
                userId,
                requestDTO.sessionId()));

        return Response.success(null);
    }

}
