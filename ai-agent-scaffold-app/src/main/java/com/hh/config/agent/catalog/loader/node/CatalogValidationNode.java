package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Catalog 入口校验节点。
 *
 * <p>本节点是所有资源处理前的闸门：先确定统一错误路径，再校验 catalog 的字段约束和版本，
 * 最后把已校验的 catalog 放入 Pipeline 上下文供后续节点使用。</p>
 */
@Component
public class CatalogValidationNode extends AgentDefinitionLoadNode {

    private final AgentDefinitionConfigSupport support;
    private final ModuleBindingNode moduleBindingNode;

    public CatalogValidationNode(AgentDefinitionConfigSupport support, ModuleBindingNode moduleBindingNode) {
        this.support = support;
        this.moduleBindingNode = moduleBindingNode;
    }

    @Override
    protected List<AgentDefinition> doApply(AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) throws Exception {
        // catalogPath 可能未显式配置；使用固定路径保证后续错误信息始终有可读的根路径。
        String path = command.getCatalog().getCatalogPath() == null || command.getCatalog().getCatalogPath().isBlank()
                ? "ai.agent.config" : command.getCatalog().getCatalogPath();

        // 保存输入和路径，后续节点只从上下文读取，避免重复依赖外部绑定对象的定位信息。
        context.setCatalog(command.getCatalog());
        context.setCatalogPath(path);

        // 先执行 Bean Validation，覆盖 catalog 的必填字段、集合约束和数值范围。
        support.validate(command.getCatalog(), path);

        // schema-version 属于编译协议版本，必须在展开模块资源前拒绝不兼容配置。
        support.requireSchemaVersion(command.getCatalog().getSchemaVersion(), path + ".schema-version");

        // 校验通过后进入资源绑定阶段；router 会调用当前节点的 get 方法选择下一节点。
        return router(command, context);
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // catalog 校验完成后，下一步才允许访问和绑定外部 YAML 资源。
        return moduleBindingNode;
    }
}
