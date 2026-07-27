package com.hh.infrastructure.agent.agent.runtime.observability.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通过 ADK 官方 Plugin 钩子采集 Agent 内部执行指标。
 *
 * <p>该系统 Plugin 由 Runner 自动注入，不受业务 YAML 控制。采集内容严格限制为 catalog 中
 * 的稳定 ID、Agent/模型/工具名称、执行结果和 token 数量；不会读取或记录 prompt、回复、
 * 工具参数、工具结果、userId、sessionId、MCP header 或异常消息。</p>
 */
public final class AgentObservabilityPlugin extends BasePlugin {

    public static final String PLUGIN_NAME = "scaffoldAgentObservability";

    static final String AGENT_TIMER_NAME = "ai.agent.adk.agent";
    static final String MODEL_TIMER_NAME = "ai.agent.model";
    static final String TOOL_TIMER_NAME = "ai.agent.tool";
    static final String MODEL_TOKEN_COUNTER_NAME = "ai.agent.model.tokens";

    private static final String UNKNOWN = "unknown";

    private final String agentId;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<OperationKey, TrackedOperation> operations = new ConcurrentHashMap<>();

    public AgentObservabilityPlugin(String agentId, MeterRegistry meterRegistry) {
        super(PLUGIN_NAME);
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public Maybe<com.google.genai.types.Content> beforeAgentCallback(
            BaseAgent agent,
            CallbackContext callbackContext) {
        String agentName = safeName(agent.name());
        start(operationKey("agent", callbackContext.invocationId(), agentName), agentName);
        return Maybe.empty();
    }

    @Override
    public Maybe<com.google.genai.types.Content> afterAgentCallback(
            BaseAgent agent,
            CallbackContext callbackContext) {
        String agentName = safeName(agent.name());
        finish(
                operationKey("agent", callbackContext.invocationId(), agentName),
                AGENT_TIMER_NAME,
                "adk.agent",
                "success");
        return Maybe.empty();
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(
            CallbackContext callbackContext,
            LlmRequest.Builder llmRequest) {
        String modelName = safeName(llmRequest.build().model().orElse(UNKNOWN));
        OperationKey key = operationKey("model", callbackContext.invocationId(), callbackContext.eventId());
        start(key, modelName);
        return Maybe.empty();
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(
            CallbackContext callbackContext,
            LlmResponse llmResponse) {
        OperationKey key = modelKey(callbackContext);
        String modelName = targetName(key);
        // usageMetadata 通常只出现在模型调用的终态响应；缺失时不估算或伪造 token。
        llmResponse.usageMetadata().ifPresent(usage -> recordTokens(modelName, usage));
        // ADK 会对流式 partial 响应逐片调用 afterModel，Timer 必须等模型调用终态再结束。
        if (isTerminalModelResponse(llmResponse)) {
            finish(key, MODEL_TIMER_NAME, "model.name", "success");
        }
        return Maybe.empty();
    }

    @Override
    public Maybe<LlmResponse> onModelErrorCallback(
            CallbackContext callbackContext,
            LlmRequest.Builder llmRequest,
            Throwable error) {
        OperationKey key = modelKey(callbackContext);
        // 不把 error 或 error.message 交给指标系统，避免供应商响应中的敏感内容进入标签。
        finish(key, MODEL_TIMER_NAME, "model.name", "error");
        finishPendingAgents(callbackContext.invocationId(), "error");
        return Maybe.empty();
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(
            BaseTool tool,
            Map<String, Object> toolArgs,
            ToolContext toolContext) {
        String toolName = safeName(tool.name());
        start(toolKey(toolContext, toolName), toolName);
        return Maybe.empty();
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(
            BaseTool tool,
            Map<String, Object> toolArgs,
            ToolContext toolContext,
            Map<String, Object> result) {
        String toolName = safeName(tool.name());
        finish(toolKey(toolContext, toolName), TOOL_TIMER_NAME, "tool.name", "success");
        return Maybe.empty();
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(
            BaseTool tool,
            Map<String, Object> toolArgs,
            ToolContext toolContext,
            Throwable error) {
        String toolName = safeName(tool.name());
        finish(toolKey(toolContext, toolName), TOOL_TIMER_NAME, "tool.name", "error");
        finishPendingAgents(toolContext.invocationId(), "error");
        return Maybe.empty();
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
        // 正常情况下各 after/error 钩子已经结束计时；这里兜底清理未配对状态，防止长期会话泄漏。
        finishPendingInvocation(invocationContext.invocationId(), "incomplete");
        return Completable.complete();
    }

    @Override
    public Completable close() {
        operations.forEach((key, ignored) -> finishPending(key, "closed"));
        operations.clear();
        return Completable.complete();
    }

    private void start(OperationKey key, String targetName) {
        TrackedOperation operation = new TrackedOperation(Timer.start(meterRegistry), targetName);
        TrackedOperation previous = operations.put(key, operation);
        if (previous != null) {
            // 同一 ADK 回调键被重复开启时先结束旧计时，避免覆盖后留下不可回收状态。
            stop(previous, timerName(key.kind()), targetTag(key.kind()), "replaced");
        }
    }

    private void finish(
            OperationKey key,
            String timerName,
            String targetTag,
            String outcome) {
        TrackedOperation operation = operations.remove(key);
        if (operation == null) {
            return;
        }
        stop(operation, timerName, targetTag, outcome);
    }

    private void stop(
            TrackedOperation operation,
            String timerName,
            String targetTag,
            String outcome) {
        stop(operation, timerName, targetTag, outcome, operation.targetName());
    }

    private void stop(
            TrackedOperation operation,
            String timerName,
            String targetTag,
            String outcome,
            String targetName) {
        operation.sample().stop(Timer.builder(timerName)
                .tags("agent.id", agentId, targetTag, safeName(targetName), "outcome", outcome)
                .register(meterRegistry));
    }

    private void finishPendingInvocation(String invocationId, String outcome) {
        operations.keySet().stream()
                .filter(key -> key.invocationId().equals(invocationId))
                .toList()
                .forEach(key -> finishPending(key, outcome));
    }

    private void finishPendingAgents(String invocationId, String outcome) {
        operations.keySet().stream()
                .filter(key -> key.kind().equals("agent") && key.invocationId().equals(safeName(invocationId)))
                .toList()
                .forEach(key -> finishPending(key, outcome));
    }

    private void finishPending(OperationKey key, String outcome) {
        TrackedOperation operation = operations.remove(key);
        if (operation != null) {
            stop(operation, timerName(key.kind()), targetTag(key.kind()), outcome);
        }
    }

    private void recordTokens(String modelName, GenerateContentResponseUsageMetadata usage) {
        usage.promptTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "prompt", value));
        usage.candidatesTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "completion", value));
        // total 是权威总量；thought/cache/tool 是明细维度，监控侧不能将所有 kind 再次求和。
        usage.thoughtsTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "thought", value));
        usage.cachedContentTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "cache", value));
        usage.toolUsePromptTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "tool", value));
        usage.totalTokenCount().ifPresent(value -> incrementTokenCounter(modelName, "total", value));
    }

    private void incrementTokenCounter(String modelName, String kind, int value) {
        if (value <= 0) {
            return;
        }
        Counter.builder(MODEL_TOKEN_COUNTER_NAME)
                .tags("agent.id", agentId, "model.name", safeName(modelName), "kind", kind)
                .register(meterRegistry)
                .increment(value);
    }

    private OperationKey modelKey(CallbackContext callbackContext) {
        return operationKey("model", callbackContext.invocationId(), callbackContext.eventId());
    }

    private OperationKey toolKey(ToolContext toolContext, String toolName) {
        String callbackId = toolContext.functionCallId().orElseGet(toolContext::eventId);
        return operationKey("tool", toolContext.invocationId(), callbackId + ":" + toolName);
    }

    private OperationKey operationKey(String kind, String invocationId, String callbackId) {
        return new OperationKey(kind, safeName(invocationId), safeName(callbackId));
    }

    private String targetName(OperationKey key) {
        TrackedOperation operation = operations.get(key);
        return operation == null ? UNKNOWN : operation.targetName();
    }

    private boolean isTerminalModelResponse(LlmResponse response) {
        if (response.turnComplete().orElse(false)) {
            return true;
        }
        return !response.partial().orElse(false);
    }

    private String timerName(String kind) {
        return switch (kind) {
            case "agent" -> AGENT_TIMER_NAME;
            case "model" -> MODEL_TIMER_NAME;
            case "tool" -> TOOL_TIMER_NAME;
            default -> throw new IllegalArgumentException("Unsupported observability operation: " + kind);
        };
    }

    private String targetTag(String kind) {
        return switch (kind) {
            case "agent" -> "adk.agent";
            case "model" -> "model.name";
            case "tool" -> "tool.name";
            default -> throw new IllegalArgumentException("Unsupported observability operation: " + kind);
        };
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private record OperationKey(String kind, String invocationId, String callbackId) {
    }

    private record TrackedOperation(Timer.Sample sample, String targetName) {
    }
}
