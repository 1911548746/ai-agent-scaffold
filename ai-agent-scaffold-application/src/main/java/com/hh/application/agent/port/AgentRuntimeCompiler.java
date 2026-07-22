package com.hh.application.agent.port;

import com.hh.domain.agent.model.definition.AgentDefinition;

import java.util.List;

/**
 * 将 Agent 定义编译并发布为可运行快照的出站端口。
 *
 * <p>application 只关心“完整编译后原子发布”的能力，不感知 ADK App、Runner、MCP 或插件的
 * 具体实现；这些细节由 infrastructure 的 ADK 适配器负责。</p>
 */
public interface AgentRuntimeCompiler {

    /**
     * 编译全部定义并原子发布。失败时必须保留上一个已发布快照。
     */
    void compileAndPublish(List<AgentDefinition> definitions) throws Exception;

    /**
     * 发布空快照，用于全局 Agent 自动装配被显式关闭的场景。
     */
    void clear();
}
