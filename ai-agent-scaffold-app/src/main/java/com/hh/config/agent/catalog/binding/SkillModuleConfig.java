package com.hh.config.agent.catalog.binding;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 一个 skills/*.yml 文件的绑定模型，描述可被 Agent 引用的 Skill 资源目录。
 *
 * <p>Skill 配置与 MCP/Local Tool 配置分开加载，Agent YAML 通过独立的 {@code skill-refs}
 * 字段引用 Skill Toolset。</p>
 */
@Data
@Validated
public class SkillModuleConfig {

    @NotNull
    @Min(1)
    private Integer schemaVersion;

    @NotBlank
    private String id;

    @NotBlank
    private String directory;

    // 仅用于在引用和校验失败时定位具体 YAML 文件。
    private transient String sourcePath;
}
