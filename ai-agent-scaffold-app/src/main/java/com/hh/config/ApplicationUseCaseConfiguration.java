package com.hh.config;

import com.hh.application.agent.service.ChatApplicationService;
import com.hh.application.agent.service.ConversationArchiveApplicationService;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * application 用例的组合根。
 *
 * <p>application 模块不承载 Spring 注解；所有外部实现从此处传入，避免 HTTP 适配器直接依赖
 * domain service 的具体编排实现。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationUseCaseConfiguration {

    @Bean
    public ChatUseCase chatUseCase(
            AgentRuntimePort agentRuntimePort,
            ConversationArchivePort conversationArchivePort,
            MessageArchivePort messageArchivePort) {
        return new ChatApplicationService(agentRuntimePort, conversationArchivePort, messageArchivePort);
    }

    @Bean
    public ConversationArchiveUseCase conversationArchiveUseCase(
            ConversationArchivePort conversationArchivePort) {
        return new ConversationArchiveApplicationService(conversationArchivePort);
    }
}
