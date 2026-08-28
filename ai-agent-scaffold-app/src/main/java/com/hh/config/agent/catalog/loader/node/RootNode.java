package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loader 策略树的根节点。
 *
 * <p>根节点不处理配置内容，只负责把一次加载请求放入策略树，并将首个实际步骤委托给
 * {@link CatalogValidationNode}。这样可以让树的入口保持稳定，同时把职责从入口类中移出。</p>
 */
@Component("AgentDefinitionLoadPipelineRootNode")
public class RootNode extends AgentDefinitionLoadNode {

    private final CatalogValidationNode catalogValidationNode;

    public RootNode(CatalogValidationNode catalogValidationNode) {
        this.catalogValidationNode = catalogValidationNode;
    }

    @Override
    protected List<AgentDefinition> doApply(AgentDefinitionLoadCommand requestParameter, AgentDefinitionLoadPipelineContext dynamicContext) throws Exception {
        // 根节点没有业务处理；先通过公共路由机制进入 catalog 校验节点。
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(AgentDefinitionLoadCommand requestParameter, AgentDefinitionLoadPipelineContext dynamicContext) throws Exception {
        // 固定返回第一步节点，后续节点由各自的 get 方法继续串接。
        return catalogValidationNode;
    }
}
