package com.hh.config.agent;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.AgentDefinitionLoader;
import com.hh.domain.agent.model.definition.AgentDefinition;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证模块化 YAML 的纯编译行为。
 *
 * <p>测试只使用 MockEnvironment 和 classpath 资源，不启动 Spring Context、数据库、真实模型或 MCP。</p>
 */
class AgentDefinitionLoaderTest {

    private static final String OFFLINE_MODEL_VALUE = "offline-model-value";

    private AgentDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        // 用非真实值验证占位符解析路径，测试中绝不需要供应商凭据。
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MODEL_API_KEY", OFFLINE_MODEL_VALUE);
        loader = new AgentDefinitionLoader(
                new PathMatchingResourcePatternResolver(new DefaultResourceLoader()),
                validator,
                environment);
    }

    @Test
    void compilesLegacyExamplesIntoEquivalentDefinitions() {
        List<AgentDefinition> definitions = loader.load("classpath:agent/catalog/default-catalog.yml");

        // catalog 中的顺序是对外展示和日志的稳定顺序，迁移后不能被 Map 遍历打乱。
        assertThat(definitions).extracting(AgentDefinition::getId)
                // 允许脚手架使用者继续增加应用，但既有示例的声明顺序必须保持稳定。
                .containsSubsequence("hello-agent", "pipeline-agent", "chrome-devtools-agent");

        // Hello 示例覆盖 Local Tool、Skill、Plugin、持久化和 slidingWindow 压缩配置的转换。
        AgentDefinition hello = definition(definitions, "hello-agent");
        assertThat(hello.isEnabled()).isTrue();
        assertThat(hello.getAppName()).isEqualTo("HelloAgent");
        assertThat(hello.getAgentDescriptor().getAgentId()).isEqualTo("100000");
        assertThat(hello.getRootAgentName()).isEqualTo("HelloAgent");
        assertThat(hello.getChatModel().getBaseUrl()).isEqualTo("https://api.siliconflow.cn");
        assertThat(hello.getChatModel().getApiKey()).isEqualTo(OFFLINE_MODEL_VALUE);
        assertThat(hello.getChatModel().getModelName()).isEqualTo("deepseek-ai/DeepSeek-V4-Flash");
        assertThat(hello.getChatModel().getTimeout()).isEqualTo(30L);
        assertThat(hello.getChatModel().getMaxRetries()).isEqualTo(4);
        assertThat(hello.getChatModel().getStreamingFirstResponseTimeout()).isEqualTo(30L);
        assertThat(hello.getChatModel().getStreamingMaxRetries()).isEqualTo(2);
        assertThat(hello.getAgents()).singleElement().satisfies(agent -> {
            assertThat(agent.getName()).isEqualTo("HelloAgent");
            assertThat(agent.getInstruction()).isEqualTo("You are a concise assistant. Say hello in a friendly way.");
            assertThat(agent.getMaxSteps()).isEqualTo(8);
            // 新增本地 Tool 只需落入受管理目录，catalog 不再维护逐文件清单。
            assertThat(agent.getLocalToolList()).isNotEmpty();
            assertThat(agent.getLocalToolList()).allSatisfy(tool ->
                    assertThat(tool.getMethodName()).isNotBlank());
            assertThat(agent.getSkillsDir()).isEqualTo("classpath:agent/skill-resources");
        });
        assertThat(hello.getRunner().getPersistence()).isTrue();
        assertThat(hello.getRunner().getGroupFunctionResponsesInHistory()).isTrue();
        assertThat(hello.getRunner().getPluginNameList()).containsExactly("myLoggingPlugin");
        assertThat(hello.getRunner().getContextCompress().getType()).isEqualTo("slidingWindow");
        // 默认示例的窗口参数以 catalog 为准，避免配置演示调整后测试仍固化旧值。
        assertThat(hello.getRunner().getContextCompress().getCompactionInterval()).isEqualTo(3);
        assertThat(hello.getRunner().getContextCompress().getOverlapSize()).isEqualTo(1);

        // Pipeline 示例覆盖三个 LLM 节点、output key 与顺序工作流的转换。
        AgentDefinition pipeline = definition(definitions, "pipeline-agent");
        assertThat(pipeline.isEnabled()).isTrue();
        assertThat(pipeline.getAppName()).isEqualTo("PipelineAgent");
        assertThat(pipeline.getAgentDescriptor().getAgentId()).isEqualTo("100001");
        assertThat(pipeline.getAgents()).extracting(agent -> agent.getName())
                .containsExactly("CodeWriterAgent", "CodeReviewerAgent", "CodeRefactorerAgent");
        assertThat(pipeline.getAgents()).extracting(agent -> agent.getOutputKey())
                .containsExactly("generated_code", "review_comments", "refactored_code");
        // 其他 Agent 未声明时由 AgentModuleConfig 的默认值统一限制为 8 步。
        assertThat(pipeline.getAgents()).extracting(agent -> agent.getMaxSteps())
                .containsOnly(8);
        assertThat(pipeline.getAgentWorkflows()).singleElement().satisfies(workflow -> {
            assertThat(workflow.getType()).isEqualTo("sequential");
            assertThat(workflow.getName()).isEqualTo("CodePipelineAgent");
            assertThat(workflow.getSubAgents())
                    .containsExactly("CodeWriterAgent", "CodeReviewerAgent", "CodeRefactorerAgent");
        });
        assertThat(pipeline.getRootAgentName()).isEqualTo("CodePipelineAgent");
        assertThat(pipeline.getRunner().getPersistence()).isTrue();
        // 未配置时保持 false，不因 ADK 升级改变历史消息组织方式。
        assertThat(pipeline.getRunner().getGroupFunctionResponsesInHistory()).isFalse();

        // Chrome 示例保留 stdio MCP 定义，但默认禁用以避免离线测试或普通启动创建外部进程。
        AgentDefinition chrome = definition(definitions, "chrome-devtools-agent");
        assertThat(chrome.isEnabled()).isFalse();
        assertThat(chrome.getAppName()).isEqualTo("ChromeDevtoolsAgent");
        assertThat(chrome.getAgentDescriptor().getAgentId()).isEqualTo("100004");
        assertThat(chrome.getAgents()).singleElement().satisfies(agent -> {
            assertThat(agent.getName()).isEqualTo("ChromeDevtoolsAgent");
            assertThat(agent.getInstruction()).contains("通过工具真实操作浏览器完成任务");
            assertThat(agent.getMcpToolList()).singleElement().satisfies(tool -> {
                assertThat(tool.getStdio()).isNotNull();
                assertThat(tool.getStdio().getCommand()).isEqualTo("cmd");
                assertThat(tool.getStdio().getArgs())
                        .containsExactly("/c", "npx", "-y", "chrome-devtools-mcp@latest");
            });
        });
        assertThat(chrome.getRunner().getPersistence()).isFalse();
        assertThat(chrome.getRunner().getGroupFunctionResponsesInHistory()).isFalse();
        assertThat(chrome.getRunner().getPluginNameList()).isEmpty();
    }

    @Test
    void rejectsDuplicateIdsWithBothConfigurationPaths() {
        // 重复 ID 必须同时指出两份资源，排查者才能直接定位冲突来源。
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/duplicate-agent-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("duplicate-agent-one.yml")
                .hasMessageContaining("duplicate-agent-two.yml")
                .hasMessageContaining("duplicate-agent");
    }

    @Test
    void compilesPureSlidingWindowRetentionSize() {
        AgentDefinition definition = loader.load("classpath:agent/catalog/pure-sliding-window-catalog.yml")
                .get(0);

        // 配置编译只传递策略参数；是否创建 ADK summarizer 由基础设施 RunnerNode 决定。
        assertThat(definition.getRunner().getContextCompress().getType()).isEqualTo("pureSlidingWindow");
        assertThat(definition.getRunner().getContextCompress().getInvocationRetentionSize()).isEqualTo(3);
    }

    @Test
    void discoversNestedModuleResourcesWithoutCatalogFileEntries() {
        AgentDefinition definition = loader.load("classpath:agent/catalog/discovery/wildcard-catalog.yml")
                .get(0);

        // application 与其他模块一样从递归目录发现，catalog 不再需要维护发布入口的内联列表。
        assertThat(definition.getId()).isEqualTo("discovered-app");
        assertThat(definition.getAppName()).isEqualTo("DiscoveredApp");
        // 未显式配置时使用安全的流式首响应默认值，旧模型 YAML 无需迁移即可加载。
        assertThat(definition.getChatModel().getStreamingFirstResponseTimeout()).isEqualTo(30L);
        assertThat(definition.getChatModel().getStreamingMaxRetries()).isEqualTo(2);
        // Tool 位于 tools 的嵌套目录中，证明 **/*.yml 能递归发现而无需逐文件登记。
        assertThat(definition.getAgents()).singleElement().satisfies(agent ->
                assertThat(agent.getLocalToolList()).singleElement().satisfies(tool -> {
                    assertThat(tool.getMethodName()).isEqualTo("getWeather");
                }));
    }

    @Test
    void deduplicatesTheSameResourceMatchedByMultipleLocations() {
        AgentDefinition definition = loader.load("classpath:agent/catalog/discovery/overlapping-catalog.yml")
                .get(0);

        // 同一 Tool 同时被递归模式和精确路径命中时不能被误报为重复 ID。
        assertThat(definition.getAgents()).singleElement().satisfies(agent ->
                assertThat(agent.getLocalToolList()).singleElement());
    }

    @Test
    void rejectsResourcePatternWithoutAnyMatchingFiles() {
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/discovery/missing-resource-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("classpath:agent-fixtures/discovery/missing-tools/**/*.yml")
                .hasMessageContaining("does not match any files");
    }

    @Test
    void rejectsUnknownReferencesWithConfigurationPath() {
        // 跨文件引用错误必须在 ADK Builder 之前失败。
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/unknown-reference-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("unknown-reference-agent.yml")
                .hasMessageContaining("tool-refs")
                .hasMessageContaining("missing-tool");
    }

    @Test
    void rejectsWorkflowCyclesWithConfigurationPaths() {
        // 循环错误需要包含完整环路和所有参与资源，而不是只报告最后访问的节点。
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/cyclic-workflow-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("cycle-a.yml")
                .hasMessageContaining("cycle-b.yml")
                .hasMessageContaining("cycle-a -> cycle-b -> cycle-a");
    }

    @Test
    void sortsWorkflowDependenciesBeforeTheirConsumers() {
        List<AgentDefinition> definitions = loader.load("classpath:agent/catalog/topological-catalog.yml");

        // 旧策略树按列表顺序创建工作流，因此子节点必须排在父节点之前。
        assertThat(definitions).singleElement().satisfies(definition ->
                assertThat(definition.getAgentWorkflows())
                        .extracting(workflow -> workflow.getName())
                        .containsExactly("ChildWorkflow", "ParentWorkflow"));
    }

    @Test
    void rejectsCatalogEntryWithoutRootAgent() {
        // 没有 root 就无法构造 App.rootAgent，不能等到 Runner 创建时再报空指针。
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/missing-root-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("missing-root-catalog.yml")
                .hasMessageContaining("root");
    }

    @Test
    void rejectsMultipleMcpTransportsWithoutLeakingHeaders() {
        // 既验证 transport 互斥，也验证错误消息没有泄露测试 header 或模型占位符的实际解析值。
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/multiple-transports-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("multiple-transports-tool.yml")
                .hasMessageContaining("exactly one transport")
                .hasMessageNotContaining("sentinel-header-value")
                .hasMessageNotContaining(OFFLINE_MODEL_VALUE);
    }

    @Test
    void rejectsLegacyLocalTransportShape() {
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/legacy-local-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("legacy-local-tool.yml")
                .hasMessageContaining("no local fields");
    }

    @Test
    void rejectsIncompleteLocalTool() {
        assertThatThrownBy(() -> loader.load("classpath:agent/catalog/invalid/invalid-local-catalog.yml"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("invalid-local-tool.yml")
                .hasMessageContaining("bean-name and method-name");
    }

    /**
     * 以稳定 ID 查找定义；测试不依赖列表下标，避免后续新增示例时产生脆弱断言。
     */
    private AgentDefinition definition(List<AgentDefinition> definitions, String id) {
        return definitions.stream()
                .filter(definition -> definition.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
