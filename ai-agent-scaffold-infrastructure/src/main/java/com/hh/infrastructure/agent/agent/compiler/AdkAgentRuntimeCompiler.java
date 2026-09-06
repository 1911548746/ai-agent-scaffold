package com.hh.infrastructure.agent.agent.compiler;

import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyPipeline;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageServiceFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

/**
 * 将领域定义编译为 ADK Runtime 并原子发布的基础设施适配器。
 *
 * <p>它实现 application 的编译端口；装配过程产生的临时对象不会离开本模块，只有全部
 * Agent 构建成功后才替换运行时 Registry。</p>
 */
@Service
public class AdkAgentRuntimeCompiler implements AgentRuntimeCompiler {

    @Resource
    private AgentAssemblyPipeline assemblyPipeline;
    @Resource
    private AdkAgentRuntimeRegistry runtimeRegistry;
    @Resource
    private ArtifactStorageServiceFactory artifactStorageServiceFactory;

    @Override
    public void compileAndPublish(List<AgentDefinition> definitions) throws Exception {

        List<AdkAgentRuntime> runtimes = new ArrayList<>();
        boolean published = false;
        try {
            for (AgentDefinition definition : definitions) {
                // 每个 Agent 使用独立上下文构建；返回值由 Registry 统一原子发布。
                AdkAgentRuntime runtime = assemblyPipeline.compile(definition);
                runtimes.add(runtime);
            }

            List<AdkAgentRuntime> candidates = List.copyOf(runtimes);
            // Lifecycle 对账属于候选 Runtime 的启动校验；失败时不能替换当前可用快照。
            artifactStorageServiceFactory.reconcileLifecycle(candidates);
            runtimeRegistry.publish(candidates);
            published = true;
        } finally {
            if (!published) {
                // 任一候选失败都不能遗留已经创建的 MCP、stdio 和观测资源。
                runtimes.forEach(AdkAgentRuntime::close);
            }
        }
    }

    @Override
    public void clear() {
        runtimeRegistry.clear();
    }

}
