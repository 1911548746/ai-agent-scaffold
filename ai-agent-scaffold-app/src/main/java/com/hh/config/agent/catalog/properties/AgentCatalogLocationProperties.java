package com.hh.config.agent.catalog.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * catalog 入口位置的部署配置。
 *
 * <p>该配置与 {@link AgentCatalogProperties} 的 catalog 内容分离，避免外部 catalog 中遗留的
 * {@code ai.agent.config.catalog-path} 覆盖实际 import 入口。它只决定相对资源的解析基准，
 * 不参与 catalog 字段绑定。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.catalog")
public class AgentCatalogLocationProperties {

    @NotBlank
    private String path = "classpath:agent/catalog/default-catalog.yml";
}
