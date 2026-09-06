package com.hh.config.agent.catalog.loader.pipeline;

import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.config.agent.catalog.loader.node.RootNode;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Service;

import java.util.List;

/** Agent Definition 加载策略树入口。 */
@Service
public class AgentDefinitionLoadPipeline {

    private final RootNode rootNode;

    public AgentDefinitionLoadPipeline(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public List<AgentDefinition> load(AgentCatalogProperties catalog) throws Exception {
        return load(catalog, null);
    }

    /**
     * 使用指定 catalog 入口执行一次加载。
     *
     * <p>catalog 对象仍由 Spring import 完成绑定；入口位置只覆盖相对资源的解析基准，
     * 不回写或修改 Spring 持有的配置对象。</p>
     */
    public List<AgentDefinition> load(AgentCatalogProperties catalog, String catalogLocation) throws Exception {
        AgentDefinitionLoadPipelineContext context = new AgentDefinitionLoadPipelineContext();
        context.setCatalogPath(catalogLocation);
        return rootNode.apply(
                new AgentDefinitionLoadCommand(catalog),
                context);
    }

}
