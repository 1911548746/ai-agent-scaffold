package com.hh.test.service;

import com.alibaba.fastjson.JSON;
import com.hh.domain.agent.model.valobj.AgentInfo;
import com.hh.domain.agent.service.chat.ChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@Slf4j
public class ChatServiceTest {

    @Resource
    private ChatService chatService;


    @Test
    public void queryAgentListTest() {
        List<AgentInfo> agentInfoList = chatService.queryAgentList();
        log.info("agentInfoList: {}", agentInfoList);
    }

    @Test
    public void createSessionTest() {
        chatService.createSession("100000", "user001");
    }

    @Test
    public void handleMessageTest_01() {
        String sessionId = chatService.createSession("100000", "user001");
        List<String> list = chatService.handleMessage("100000", "user001", sessionId, "你好");
        log.info("测试结果: {}", JSON.toJSONString(list));
    }


}
