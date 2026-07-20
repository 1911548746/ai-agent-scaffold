package com.hh.domain.agent.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.IArmoryService;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    // TODO 迁移至新装配链
    @Override
    public List<AiAgentRegisterVO> acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {

        List<AiAgentRegisterVO> registers = new ArrayList<>();

        for (AiAgentConfigTableVO table : tables) {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> strategyHandler = defaultArmoryFactory.armoryStrategyHandler();
            // 每个 Agent 使用独立上下文构建；返回值由 AgentCompiler 统一原子发布。
            AiAgentRegisterVO registerVO = strategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext()
            );
            registers.add(registerVO);
        }

        return List.copyOf(registers);
    }

}
