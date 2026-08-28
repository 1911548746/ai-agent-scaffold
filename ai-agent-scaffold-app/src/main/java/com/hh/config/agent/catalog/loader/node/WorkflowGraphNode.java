package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadContext;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 依赖图节点。
 *
 * <p>前置条件是模块引用已经校验通过。本节点使用 DFS 三色标记检测循环，并以后序顺序写入
 * workflow ID 列表，保证子工作流在父工作流之前进入领域 Definition。</p>
 *
 * <p>图算法与资源加载、Definition 转换隔离；发现回边时同时报告完整环路和参与资源路径，
 * 使启动失败可以直接定位到配置文件。</p>
 */
@Component
public class WorkflowGraphNode extends AgentDefinitionLoadNode {

    private final AgentDefinitionConfigSupport support;
    private final ApplicationBindingNode applicationBindingNode;

    public WorkflowGraphNode(AgentDefinitionConfigSupport support, ApplicationBindingNode applicationBindingNode) {
        this.support = support;
        this.applicationBindingNode = applicationBindingNode;
    }

    @Override
    protected List<AgentDefinition> doApply(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext pipelineContext) throws Exception {
        // 图阶段只消费前置节点产生的 Workflow 索引，不再处理资源绑定或字段语义校验。
        AgentDefinitionLoadContext context = pipelineContext.getLoadContext();

        // states 记录每个节点的 DFS 状态，order 保存依赖优先的后序结果，path 保存当前递归链。
        Map<String, VisitState> states = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();

        // 从每个未处理的 Workflow 出发，覆盖可能不属于同一棵树的多个图分量。
        context.getWorkflows().keySet().forEach(id -> visit(id, context.getWorkflows(), states, path, order));

        // 后序遍历保证子 Workflow 先于父 Workflow 编译，结果写入上下文供 Application 使用。
        context.setWorkflowOrder(order);
        return router(command, pipelineContext);
    }

    private void visit(
            String id,
            Map<String, WorkflowModuleConfig> workflows,
            Map<String, VisitState> states,
            Deque<String> path,
            List<String> order) {
        // 已完成节点无需重复遍历；这同时避免多个父节点共享子图时重复追加顺序。
        VisitState state = states.get(id);
        if (state == VisitState.VISITED) return;
        if (state == VisitState.VISITING) {
            // 再次遇到正在访问的节点说明发现回边，当前 path 中从该节点开始的部分就是环路。
            List<String> currentPath = new ArrayList<>(path);
            int cycleStart = currentPath.indexOf(id);
            List<String> cycle = new ArrayList<>(currentPath.subList(cycleStart, currentPath.size()));
            cycle.add(id);

            // 同时汇总环路涉及的资源文件，使启动错误可以直接定位到配置来源。
            String resources = cycle.stream()
                    .map(workflows::get)
                    .map(WorkflowModuleConfig::getSourcePath)
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("workflow");
            throw support.error(resources, "workflow cycle detected: " + String.join(" -> ", cycle));
        }

        // 入栈前标记为 VISITING；后续边若回到该状态即可识别循环依赖。
        states.put(id, VisitState.VISITING);
        path.addLast(id);

        // 递归访问所有子 Workflow；引用有效性已在上一节点完成，因此这里可直接取配置。
        for (String child : workflows.get(id).getSubWorkflowRefs()) {
            visit(child, workflows, states, path, order);
        }

        // 所有子节点完成后出栈并标记完成，再追加当前节点形成拓扑后序序列。
        path.removeLast();
        states.put(id, VisitState.VISITED);
        order.add(id);
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // 图检查完成后交由 Application 节点决定实际入口和可达资源。
        return applicationBindingNode;
    }

    private enum VisitState { VISITING, VISITED }
}
