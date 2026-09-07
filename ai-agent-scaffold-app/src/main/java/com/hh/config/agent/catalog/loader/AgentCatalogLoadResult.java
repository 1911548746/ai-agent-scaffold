package com.hh.config.agent.catalog.loader;

import com.hh.domain.agent.model.definition.AgentDefinition;

import java.util.List;

/**
 * 一次完整 catalog 加载的不可变结果。
 *
 * <p>摘要只代表本次加载实际读取的资源快照，不包含配置对象的可变引用或原始配置内容。</p>
 */
public record AgentCatalogLoadResult(
        List<AgentDefinition> definitions,
        String configDigest) {

    public AgentCatalogLoadResult {
        definitions = List.copyOf(definitions);
    }
}
