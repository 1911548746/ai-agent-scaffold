package com.hh.config.agent;

import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.domain.agent.model.definition.AgentDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将已完成 YAML 解析的 AgentDefinition 编译为 ADK Runtime，并以原子方式发布。
 *
 * <p>此类刻意不把中间 Runtime 写入 Spring 容器：所有 Agent 都必须先完成 App、Runner、
 * 模型和工具资源构建，才允许替换 Registry 的整份快照。</p>
 */
@Component
@Slf4j
public class AgentCompiler {

    private final AgentRuntimeCompiler runtimeCompiler;

    public AgentCompiler(AgentRuntimeCompiler runtimeCompiler) {
        this.runtimeCompiler = runtimeCompiler;
    }

    /**
     * 编译并发布全部启用定义。任何异常都会阻止 publish，旧快照保持可用。
     */
    public void compileAndPublish(List<AgentDefinition> definitions) {
        validateRuntimeAgentIds(definitions);

        try {
            // 基础设施先完成所有 ADK 资源构建，再由其 Registry 一次性替换快照。
            runtimeCompiler.compileAndPublish(definitions);
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            // SDK 异常可能携带模型或 MCP 细节，只记录异常类型且不保留 cause 给启动日志展开。
            log.error("Agent catalog ADK assembly failed, errorType={}", exception.getClass().getSimpleName());
            throw new AgentConfigurationException("Agent catalog ADK assembly failed");
        }
    }

    /**
     * Registry 的键是 agentId，必须在创建任何外部工具前完成唯一性检查并保留配置定位信息。
     */
    private void validateRuntimeAgentIds(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> definitionsByAgentId = new HashMap<>();
        for (AgentDefinition definition : definitions) {
            String agentId = definition.getAgentDescriptor().getAgentId();
            AgentDefinition previous = definitionsByAgentId.putIfAbsent(agentId, definition);
            if (previous != null) {
                throw new AgentConfigurationException(definition.getSourcePath()
                        + ": duplicate agentId '" + agentId + "'; first defined in "
                        + previous.getSourcePath());
            }
        }
    }
}
