package com.hh.domain.agent.model.valobj.properties;


import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TODO 迁移至 app 包
 * <p>AiAgentAutoConfigProperties 带有 @ConfigurationProperties、@Validated 和 Spring 绑定注解，
 * 且描述的是新版 catalog 的配置绑定入口，不是旧的 AiAgentConfigTableVO。它放在 domain 已经不太合适
 * <br>
 * 但是 AiAgentAutoConfigProperties 不能只改包直接迁到 app：当前 ChatService.java 直接依赖它读取 tables，会形成 domain 依赖 app 的反向依赖。
 * 正确做法是后续先让 ChatService 依赖一个领域查询端口（例如 AgentCatalog / AgentDefinitionQuery），
 * 再由 app 用配置对象实现该端口，最后迁移 Properties
 * </p>
 */

/**
 * catalog 文件在 Spring Environment 中的绑定入口。
 *
 * <p>前半部分保存模块资源位置和对外 application 描述；tables 是编译后为旧 HTTP/Armory
 * 调用方保留的运行时视图，两者不能混为一谈。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.config", ignoreUnknownFields = false)
public class AiAgentAutoConfigProperties {

    // 全局开关关闭时不读取模块资源，也不创建任何 Runner。
    private boolean enabled = false;

    // schema-version 必须显式声明，防止新旧 YAML 结构被静默按错误规则解释。
    @NotNull
    @Min(1)
    private Integer schemaVersion;

    // 用于将绑定、校验或引用错误定位回 catalog 文件，而不是输出整份配置内容。
    private String catalogPath;

    // 各类模块只保存资源地址；真正的对象由 AgentDefinitionLoader 延迟读取并绑定。
    @NotNull
    private List<@NotBlank String> modelResources = new ArrayList<>();

    @NotNull
    private List<@NotBlank String> toolResources = new ArrayList<>();

    @NotNull
    private List<@NotBlank String> agentResources = new ArrayList<>();

    @NotNull
    private List<@NotBlank String> workflowResources = new ArrayList<>();

    @Valid
    @NotNull
    private List<ApplicationDefinition> applications = new ArrayList<>();

    // 编译后的兼容视图：ChatService 仍通过它查询当前可用 Agent，不参与 YAML 绑定。
    private Map<String, AiAgentConfigTableVO> tables = new LinkedHashMap<>();

    /**
     * 仅在启用配置时要求 catalog 完整；禁用模式允许应用不导入任何 Agent 资源而正常启动。
     */
    @AssertTrue(message = "enabled catalog requires catalog-path, model-resources, agent-resources and applications")
    public boolean isCatalogComplete() {
        return !enabled
                || (schemaVersion != null
                && hasText(catalogPath)
                && !modelResources.isEmpty()
                && !agentResources.isEmpty()
                && !applications.isEmpty());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * catalog 中一个可对外发布的 application。
     *
 * <p>agentRef 与 workflowRef 的“二选一”规则需要访问全局 ID 表，因此由 Loader 校验。</p>
     */
    @Data
    public static class ApplicationDefinition {
        @NotBlank
        private String id;
        // 单个 application 的发布开关；禁用项仍会参与静态配置校验。
        private boolean enabled = true;
        @NotBlank
        private String appName;
        @NotBlank
        private String agentId;
        @NotBlank
        private String agentName;
        @NotBlank
        private String agentDesc;
        private String agentRef;
        private String workflowRef;
        @Valid
        @NotNull
        private RunnerDefinition runner = new RunnerDefinition();
    }

    /**
     * 当前 Runner 所需的产品级配置。root agent 名称由引用解析得到，不能由 YAML 再单独指定。
     */
    @Data
    public static class RunnerDefinition {
        // 未声明持久化时保持旧装配链默认的内存 Session 行为。
        private boolean persistence = false;
        @Valid
        @NotNull
        private ContextCompressDefinition contextCompress = new ContextCompressDefinition();
        @NotNull
        private List<@NotBlank String> pluginNameList = new ArrayList<>();
    }

    /**
     * 上下文压缩的默认参数，与旧 Module.ContextCompress 保持一致以避免迁移后改变对话行为。
     */
    @Data
    public static class ContextCompressDefinition {
        @NotBlank
        private String type = "slidingWindow";
        @Min(1)
        private Integer compactionInterval = 12;
        @Min(1)
        private Integer overlapSize = 2;
        @Min(1)
        private Integer tokenThreshold = 12000;
        @Min(1)
        private Integer eventRetentionSize = 30;
    }

}
