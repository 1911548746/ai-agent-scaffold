package com.hh.trigger.http;

import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.trigger.http.agent.AgentServiceController;
import com.hh.trigger.http.agent.parser.MultimodalAttachmentParser;
import com.hh.trigger.http.agent.ConversationController;
import com.hh.trigger.http.auth.AuthenticatedPrincipalContext;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.handler.GlobalExceptionHandler;
import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 API-01 的请求边界；测试不加载 Spring Context，因此不会连接模型或数据库。
 */
class ApiValidationTest {

    private static Validator validator;
    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @BeforeEach
    void setPrincipal() {
        // standalone MockMvc 不会自动创建 Spring AOP 代理，测试中显式提供已认证主体。
        AuthenticatedPrincipalContext.set(new AuthenticatedPrincipal("user-1", "test-user"));
    }

    @AfterAll
    static void closeValidator() {
        // ValidatorFactory 可能持有缓存；测试结束后主动释放，避免污染其他离线测试。
        validatorFactory.close();
    }

    @AfterEach
    void clearPrincipal() {
        AuthenticatedPrincipalContext.clear();
    }

    @Test
    void chatRejectsBlankMessageWithConsistentBadRequestAndSkipsService() throws Exception {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        MockMvc mvc = agentMvc(chatUseCase);

        mvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"hello\",\"userId\":\"user-1\",\"sessionId\":\"session-1\",\"message\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("0002"))
                .andExpect(jsonPath("$.info").value("message不能为空"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(chatUseCase);
    }

    @Test
    void chatRejectsOversizedTextBeforeCallingModelService() throws Exception {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        MockMvc mvc = agentMvc(chatUseCase);
        String oversizedMessage = "x".repeat(RequestPayloadLimits.MAX_TEXT_LENGTH + 1);

        mvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"hello\",\"userId\":\"user-1\",\"sessionId\":\"session-1\",\"message\":\"" + oversizedMessage + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("0002"));

        verifyNoInteractions(chatUseCase);
    }

    @Test
    void malformedJsonUsesSameSafeBadRequestContract() throws Exception {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        MockMvc mvc = agentMvc(chatUseCase);

        mvc.perform(post("/api/v1/create-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("0002"))
                .andExpect(jsonPath("$.info").value("请求参数不合法"));

        verifyNoInteractions(chatUseCase);
    }

    @Test
    void createSessionRejectsBlankAgentIdBeforeCreatingSession() throws Exception {
        ChatUseCase chatUseCase = mock(ChatUseCase.class);
        MockMvc mvc = agentMvc(chatUseCase);

        mvc.perform(post("/api/v1/create-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"\",\"userId\":\"user-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("0002"));

        verifyNoInteractions(chatUseCase);
    }

    @Test
    void deleteRejectsMissingConversationIdBeforeTouchingServices() throws Exception {
        ConversationArchiveUseCase archiveUseCase = mock(ConversationArchiveUseCase.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(conversationController(archiveUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/conversations/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"hello\",\"userId\":\"user-1\",\"sessionId\":\"session-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("0002"));

        verifyNoInteractions(archiveUseCase);
    }

    @Test
    void deleteDelegatesOnlyToUnifiedArchiveUseCase() throws Exception {
        ConversationArchiveUseCase archiveUseCase = mock(ConversationArchiveUseCase.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(conversationController(archiveUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/conversations/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"hello\",\"userId\":\"user-1\","
                                + "\"sessionId\":\"session-1\",\"conversationId\":10}"))
                // Response<Void> 在 standalone MockMvc 下沿用历史空响应表现；这里仅固定删除编排入口。
                .andExpect(status().isOk());

        verify(archiveUseCase).deleteConversation(new com.hh.application.agent.model.ConversationDeletionCommand(
                10L, "hello", "user-1", "session-1"));
    }

    @Test
    void inlineDataHasPerItemAndAggregateSizeLimits() {
        ChatCommandEntity.Content.InlineData tooLarge = new ChatCommandEntity.Content.InlineData(
                new byte[RequestPayloadLimits.MAX_INLINE_DATA_BYTES + 1], "application/octet-stream");
        ChatCommandEntity perItemLimitExceeded = ChatCommandEntity.builder()
                .agentId("hello")
                .userId("user-1")
                .sessionId("session-1")
                .inlineDatas(List.of(tooLarge))
                .build();

        assertThat(validator.validate(perItemLimitExceeded))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("inlineDatas[0].bytes"));

        // 每项均小于 4MiB，但三项之和超过 8MiB，必须被聚合约束拒绝。
        ChatCommandEntity.Content.InlineData validSingleButLargeTotal = new ChatCommandEntity.Content.InlineData(
                new byte[RequestPayloadLimits.MAX_INLINE_DATA_TOTAL_BYTES / 3 + 1], "application/octet-stream");
        ChatCommandEntity aggregateLimitExceeded = ChatCommandEntity.builder()
                .agentId("hello")
                .userId("user-1")
                .sessionId("session-1")
                .inlineDatas(List.of(validSingleButLargeTotal, validSingleButLargeTotal, validSingleButLargeTotal))
                .build();

        assertThat(validator.validate(aggregateLimitExceeded))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("inlineDataTotalSizeValid"));
    }

    private MockMvc agentMvc(ChatUseCase chatUseCase) {
        return MockMvcBuilders.standaloneSetup(
                        new AgentServiceController(chatUseCase, new MultimodalAttachmentParser()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ConversationController conversationController(ConversationArchiveUseCase archiveUseCase) {
        return new ConversationController(archiveUseCase);
    }
}
