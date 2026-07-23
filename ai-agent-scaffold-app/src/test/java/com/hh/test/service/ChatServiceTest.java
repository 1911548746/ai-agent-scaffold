package com.hh.test.service;

import com.alibaba.fastjson.JSON;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@Slf4j
public class ChatServiceTest {

    @Resource
    // Spring Context 中暴露的是 application 入站用例，测试不能再依赖已删除的 domain 编排类。
    private ChatUseCase chatService;


    @Test
    public void queryAgentListTest() {
        List<AgentDescriptor> agentInfoList = chatService.queryAgentList();
        log.info("agentInfoList: {}", agentInfoList);
    }

    @Test
    public void createSessionTest() {
        chatService.createSession("100000", "user001");
    }

    @Test
    public void handleMessageTest_01() {
        String sessionId = chatService.createSession("100000", "user001");
        ChatResult result = chatService.handleMessage("100000", "user001", sessionId, "你好");
        log.info("测试结果: {}", JSON.toJSONString(result));
    }


}
