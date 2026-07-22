package com.hh.infrastructure.agent.runtime;

import com.hh.domain.agent.model.definition.AgentDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommand {

    // 仅接收已完成 YAML 绑定、引用解析和图校验的定义，避免装配节点自行解释原始配置。
    private AgentDefinition agentDefinition;

}
