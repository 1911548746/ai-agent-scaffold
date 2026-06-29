package com.hh.config;

import com.alibaba.fastjson.JSON;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.hh.domain.agent.service.IArmoryService;
import com.hh.domain.agent.service.armory.ArmoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            for (AiAgentConfigTableVO aiAgentConfigTableVO : aiAgentAutoConfigProperties.getTables().values()) {
                log.info("Ai Agent 智能体装配 {}", JSON.toJSONString(aiAgentConfigTableVO));
            }

            armoryService.acceptArmoryAgents(new ArrayList<>(aiAgentAutoConfigProperties.getTables().values()));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
