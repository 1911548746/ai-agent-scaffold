package com.hh.infrastructure.agent.agent.compiler.assembly.command;

import com.hh.domain.agent.model.definition.AgentDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
/**
 * 单个 Agent 的装配输入。
 *
 * <p>它不是 HTTP 或 application command，只在 compiler 的策略树内部传递已校验的定义。</p>
 */
public class AgentAssemblyCommand {

    // 仅接收已完成 YAML 绑定、引用解析和图校验的定义，避免装配节点自行解释原始配置。
    private AgentDefinition agentDefinition;

}
