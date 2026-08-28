package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Definition 加载结束节点。
 *
 * <p>前置节点已经完成资源绑定、引用校验、Workflow 图检查和领域对象编译。本节点不再进行
 * 转换或校验，只把上下文中的不可变候选列表作为 Pipeline 结果返回。</p>
 */
@Component("agentDefinitionLoadPipelineEndNode")
public class EndNode extends AgentDefinitionLoadNode {

    @Override
    protected List<AgentDefinition> doApply(AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // 编译节点已经通过 List.copyOf 写入候选结果，这里直接返回同一份快照。
        return context.getLoadContext().getDefinitions();
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // 结束节点没有后继步骤；使用框架默认处理器终止策略树。
        return defaultStrategyHandler;
    }
}
