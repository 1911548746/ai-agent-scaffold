package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.binding.AgentModuleConfig;
import com.hh.config.agent.catalog.binding.ModelModuleConfig;
import com.hh.config.agent.catalog.binding.SkillModuleConfig;
import com.hh.config.agent.catalog.binding.ToolModuleConfig;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadContext;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.enums.AgentWorkflowTypeEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 模块语义校验节点。
 *
 * <p>前置条件是所有资源已经绑定并建立 ID 索引。本节点只做模块自身 schema、模型占位符、Tool
 * transport 互斥以及 Agent/Workflow 跨模块引用校验；它不执行 DFS，也不生成 Definition。</p>
 *
 * <p>将这些规则集中在同一阶段可以保证所有 ID 表完整后再解析引用，错误始终定位到具体 YAML
 * 文件和字段，而不是依赖资源发现顺序。</p>
 */
@Component
public class ModuleValidationNode extends AgentDefinitionLoadNode {

    private final AgentDefinitionConfigSupport support;
    private final WorkflowGraphNode workflowGraphNode;

    public ModuleValidationNode(AgentDefinitionConfigSupport support, WorkflowGraphNode workflowGraphNode) {
        this.support = support;
        this.workflowGraphNode = workflowGraphNode;
    }

    @Override
    protected List<AgentDefinition> doApply(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext pipelineContext) throws Exception {
        // 从上下文取得已经完成绑定和索引的模块；本阶段不再重新扫描文件。
        AgentDefinitionLoadContext context = pipelineContext.getLoadContext();

        // 按模块依赖方向执行校验：基础模型/工具先校验，Agent 和 Workflow 再解析引用。
        validateModels(context.getModels());
        validateTools(context.getTools());
        validateSkills(context.getTools(), context.getSkills());
        validateAgents(context.getModels(), context.getTools(), context.getSkills(), context.getAgents());
        validateWorkflows(context.getAgents(), context.getWorkflows());

        // 所有 ID 表和跨模块引用都有效后，才进入需要图算法的 Workflow 阶段。
        return router(command, pipelineContext);
    }

    private void validateModels(Map<String, ModelModuleConfig> models) {
        models.values().forEach(model -> {
            // 每个模块必须声明当前编译器支持的 schema 版本。
            support.requireSchemaVersion(model.getSchemaVersion(), model.getSourcePath() + ".schema-version");

            // 模型连接信息允许使用环境变量占位符；解析结果回写 DTO，供后续领域编译使用。
            model.setBaseUrl(support.resolveRequired(model.getBaseUrl(), model.getSourcePath() + ".base-url"));
            model.setApiKey(support.resolveRequired(model.getApiKey(), model.getSourcePath() + ".api-key"));
            model.setModelName(support.resolveRequired(model.getModelName(), model.getSourcePath() + ".model-name"));
        });
    }

    private void validateTools(Map<String, ToolModuleConfig> tools) {
        tools.values().forEach(tool -> {
            // 先验证版本，再根据 type 选择 MCP 或本地 Bean 方法的互斥规则。
            support.requireSchemaVersion(tool.getSchemaVersion(), tool.getSourcePath() + ".schema-version");
            if ("mcp".equalsIgnoreCase(tool.getType())) {
                // MCP 工具不能混入本地工具字段，且必须由一个 transport 提供连接方式。
                if (tool.getMcp() == null || tool.getBeanName() != null || tool.getMethodName() != null) {
                    throw support.error(tool.getSourcePath(), "MCP tool must configure exactly one transport and no local fields");
                }
                // 分别检查 SSE、HTTP、stdio 是否配置，确保 transport 选择是唯一的。
                int transports = countPresent(tool.getMcp().getSse(), tool.getMcp().getHttp(), tool.getMcp().getStdio());
                if (transports != 1) {
                    throw support.error(tool.getSourcePath() + ".mcp", "MCP tool must configure exactly one transport");
                }
            } else if ("local".equalsIgnoreCase(tool.getType())) {
                // 本地工具不允许出现 MCP 配置，并且必须能定位到 Spring Bean 方法。
                if (tool.getMcp() != null) {
                    throw support.error(tool.getSourcePath(), "Local tool cannot configure mcp transport");
                }
                if (!support.hasText(tool.getBeanName()) || !support.hasText(tool.getMethodName())) {
                    throw support.error(tool.getSourcePath(), "Local tool must configure bean-name and method-name");
                }
            } else {
                // 未知 type 不能被默认解释为某一种工具，避免配置拼写错误静默生效。
                throw support.error(tool.getSourcePath() + ".type", "unsupported tool type '" + tool.getType() + "'");
            }
        });
    }

    private void validateSkills(Map<String, ToolModuleConfig> tools, Map<String, SkillModuleConfig> skills) {
        // Skill 先独立完成 schema 校验，再与 Tool 共享能力 ID 做跨类别冲突检查。
        skills.values().forEach(skill ->
                support.requireSchemaVersion(skill.getSchemaVersion(), skill.getSourcePath() + ".schema-version"));
        skills.forEach((id, skill) -> {
            // Tool 和 Skill 使用不同引用字段，但 ID 仍必须避免歧义。
            ToolModuleConfig tool = tools.get(id);
            if (tool != null) {
                throw support.error(tool.getSourcePath() + ", " + skill.getSourcePath(), "duplicate capability id '" + id + "'");
            }
        });
    }

    private void validateAgents(
            Map<String, ModelModuleConfig> models,
            Map<String, ToolModuleConfig> tools,
            Map<String, SkillModuleConfig> skills,
            Map<String, AgentModuleConfig> agents) {
        agents.values().forEach(agent -> {
            // Agent 的版本和模型引用先校验，再检查它声明的 Tool/Skill 列表。
            support.requireSchemaVersion(agent.getSchemaVersion(), agent.getSourcePath() + ".schema-version");
            if (!models.containsKey(agent.getModelRef())) {
                throw support.error(agent.getSourcePath() + ".model-ref", "unknown model id '" + agent.getModelRef() + "'");
            }
            validateRefs(agent.getToolRefs(), tools, agent.getSourcePath() + ".tool-refs", "tool");
            validateRefs(agent.getSkillRefs(), skills, agent.getSourcePath() + ".skill-refs", "skill");
        });
    }

    private void validateWorkflows(Map<String, AgentModuleConfig> agents, Map<String, WorkflowModuleConfig> workflows) {
        workflows.values().forEach(workflow -> {
            // Workflow 的类型必须能映射到领域枚举，引用则必须命中已建立的 ID 索引。
            support.requireSchemaVersion(workflow.getSchemaVersion(), workflow.getSourcePath() + ".schema-version");
            if (AgentWorkflowTypeEnum.getByType(workflow.getType()) == null) {
                throw support.error(workflow.getSourcePath() + ".type", "unsupported workflow type '" + workflow.getType() + "'");
            }
            validateRefs(workflow.getSubAgentRefs(), agents, workflow.getSourcePath() + ".sub-agent-refs", "agent");
            validateRefs(workflow.getSubWorkflowRefs(), workflows, workflow.getSourcePath() + ".sub-workflow-refs", "workflow");
            if (workflow.getSubAgentRefs().isEmpty() && workflow.getSubWorkflowRefs().isEmpty()) {
                // 空 Workflow 没有可装配内容，提前拒绝，避免图阶段产生无意义节点。
                throw support.error(workflow.getSourcePath(), "workflow must reference at least one agent or workflow");
            }
        });
    }

    private <T> void validateRefs(List<String> refs, Map<String, T> values, String path, String kind) {
        // 保留引用下标，错误可以精确指向 YAML 数组中的具体元素。
        for (int i = 0; i < refs.size(); i++) {
            if (!values.containsKey(refs.get(i))) {
                throw support.error(path + "[" + i + "]", "unknown " + kind + " id '" + refs.get(i) + "'");
            }
        }
    }

    private int countPresent(Object... values) {
        // null 表示 transport 未配置；只统计存在的配置对象，不读取其中的敏感字段。
        int count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // 模块自身和跨模块引用全部通过后，才允许执行 Workflow 图算法。
        return workflowGraphNode;
    }
}
