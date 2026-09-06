package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.events.Event;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.SessionLease;
import com.hh.application.agent.port.SessionOperationLockPort;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.runtime.invocation.ModelInvocationFailureMapper;
import com.hh.infrastructure.agent.agent.runtime.invocation.SessionInvocationCoordinator;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Google ADK Runner 的运行时适配器。
 *
 * <p>此处是唯一读取 ADK Runtime 快照并调用 Runner 的位置。application 只面向
 * {@link AgentRuntimePort} 编排消息归档和状态，不再持有 SDK Runner。</p>
 */
@Service
public class AdkAgentRuntimeAdapter implements AgentRuntimePort, SessionOperationLockPort {

    private final AdkAgentRuntimeRegistry runtimeRegistry;
    private final SessionInvocationCoordinator compactionCoordinator;
    public AdkAgentRuntimeAdapter(
            AdkAgentRuntimeRegistry runtimeRegistry,
            SessionInvocationCoordinator compactionCoordinator) {
        this.runtimeRegistry = runtimeRegistry;
        this.compactionCoordinator = compactionCoordinator;
    }

    @Override
    /** 返回当前已发布 Runtime 的对外 Agent 描述，不暴露 ADK Runner。 */
    public List<AgentDescriptor> listAgents() {
        return runtimeRegistry.descriptors();
    }

    @Override
    /** 根据 Agent ID 返回其已编译的 Application 名称。 */
    public String appName(String agentId) {
        try (RuntimeLease lease = acquireRuntime(agentId)) {
            return lease.runtime().appName();
        }
    }

    @Override
    /** 为用户创建 Session，并使用当前 Runtime 绑定的 SessionService 保存。 */
    public String createSession(String agentId, String userId) {
        try (RuntimeLease lease = acquireRuntime(agentId)) {
            AdkAgentRuntime runtime = lease.runtime();
            Session session = runtime.runner().sessionService()
                    .createSession(runtime.appName(), userId)
                    .blockingGet();
            return session.id();
        }
    }

    @Override
    /** 校验用户是否拥有指定的完整 Session 身份。 */
    public void requireSession(String agentId, String userId, String sessionId) {
        try (RuntimeLease lease = acquireRuntime(agentId)) {
            AdkAgentRuntime runtime = lease.runtime();
            Session session = runtime.runner().sessionService()
                    .getSession(runtime.appName(), userId, sessionId, Optional.empty())
                    .blockingGet();
            if (session == null) {
                throw new AppException(ResponseCode.SESSION_NOT_FOUND);
            }
        }
    }

    @Override
    /** 为删除或归档等操作取得与 Agent 调用共用的 Session 独占租约。 */
    public SessionLease acquire(String agentId, String userId, String sessionId) {
        RuntimeLease runtimeLease = acquireRuntime(agentId);
        try {
            return compactionCoordinator.acquireSessionOperationLease(
                    runtimeLease, userId, sessionId);
        } catch (RuntimeException | Error error) {
            runtimeLease.close();
            throw error;
        }
    }

    /**
     * 执行文本同步调用。
     *
     * <p>同步消费完整 Event 流，只向 application 返回可展示文本和人工确认请求；调用结束后由租约
     * 触发后置压缩，异常路径则进入 invocation 恢复。</p>
     */
    @Override
    public ChatResult run(String agentId, String userId, String sessionId, String message) {

        List<String> outputs = new ArrayList<>();
        List<ToolConfirmationRequest> confirmations = new ArrayList<>();
        Set<String> emittedConfirmations = new HashSet<>();

        // 先构造纯输入对象；只有进入 invocation 后才把 Runtime lease 交给 Coordinator 托管。
        Content content = Content.fromParts(Part.fromText(message));
        RuntimeLease runtimeLease = acquireRuntime(agentId);
        AdkAgentRuntime runtime = runtimeLease.runtime();

        SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                runtimeLease, userId, sessionId, content);
        try {
            runAsync(runtime, userId, sessionId, content, runtime.runConfig())
                    .blockingForEach(event -> appendFinalResponse(
                            outputs, confirmations, emittedConfirmations, event));
            lease.success();
            return new ChatResult(outputs, confirmations);
        } catch (RuntimeException | Error error) {
            lease.fail();
            throw error;
        }
    }

    /** 创建文本流式调用；真正订阅时才取得 Session 租约并启动 ADK Flowable。 */
    @Override
    public ChatStream runStream(String agentId, String userId, String sessionId, String message) {
        Content content = Content.fromParts(Part.fromText(message));
        return runStream(agentId, userId, sessionId, content);
    }

    /** 执行多模态同步调用，附件以 Artifact 名称进入请求并由 LoadArtifactsTool 按需加载。 */
    @Override
    public ChatResult run(String agentId, ChatCommandEntity command) {
        List<String> outputs = new ArrayList<>();
        List<ToolConfirmationRequest> confirmations = new ArrayList<>();
        Set<String> emittedConfirmations = new HashSet<>();
        // commandContent 不访问 Runtime，提前执行可保证其异常不会遗留已获取的 generation lease。
        Content content = commandContent(command);
        RuntimeLease runtimeLease = acquireRuntime(agentId);
        AdkAgentRuntime runtime = runtimeLease.runtime();
        SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                runtimeLease, command.getUserId(), command.getSessionId(), content);
        try {
            runAsync(runtime, command.getUserId(), command.getSessionId(), content, runtime.runConfig())
                    .blockingForEach(event -> appendFinalResponse(
                            outputs, confirmations, emittedConfirmations, event));
            lease.success();
            return new ChatResult(outputs, confirmations);
        } catch (RuntimeException | Error error) {
            lease.fail();
            throw error;
        }
    }

    /** 创建多模态流式调用，并复用统一的流式租约、取消和失败恢复路径。 */
    @Override
    public ChatStream runStream(String agentId, ChatCommandEntity command) {
        return runStream(agentId, command.getUserId(), command.getSessionId(), commandContent(command));
    }

    /**
     * 统一处理文本和多模态流，确保两种入口使用相同的租约和取消语义。
     *
     * <p>订阅错误、正常完成、客户端取消和订阅建立异常分别收敛到租约的 fail/success 终态方法，
     * 其中租约本身保证终态操作幂等。</p>
     */
    private ChatStream runStream(
            String agentId,
            String userId,
            String sessionId,
            Content content) {
        return observer -> {
            RuntimeLease runtimeLease = acquireRuntime(agentId);
            AdkAgentRuntime runtime = runtimeLease.runtime();
            RunConfig runConfig = runtime.runConfig().toBuilder()
                    .streamingMode(RunConfig.StreamingMode.SSE)
                    .build();
            SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                    runtimeLease, userId, sessionId, content);
            try {
                Flowable<Event> events = runAsync(runtime, userId, sessionId, content, runConfig);
                AtomicBoolean partialTextEmitted = new AtomicBoolean(false);
                Set<String> emittedConfirmations = new HashSet<>();
                var disposable = events.subscribe(
                // TODO 后期可以加入 思考过程、工具调用 等 trace，在前端展示
                event -> emitStreamingResponse(observer, event, partialTextEmitted, emittedConfirmations),
                error -> {
                    lease.fail();
                    observer.onError(error);
                },
                () -> {
                    lease.success();
                    observer.onComplete();
                }
                );
                return () -> {
                    // 先停止上游继续产出 Event，再在同一 Session 许可内清理本轮已落地的上下文。
                    disposable.dispose();
                    lease.fail();
                };
            } catch (RuntimeException | Error error) {
                lease.fail();
                throw error;
            }
        };
    }

    /** 执行 HITL 同步恢复调用，将确认结果编码为 ADK 保留函数的 FunctionResponse。 */
    @Override
    public ChatResult runConfirmation(
            String agentId,
            String userId,
            String sessionId,
            String confirmationCallId,
            boolean confirmed) {
        // 恢复输入不是自然语言消息，而是 ADK 识别的确认 FunctionResponse。
        List<String> outputs = new ArrayList<>();
        List<ToolConfirmationRequest> confirmations = new ArrayList<>();
        Set<String> emittedConfirmations = new HashSet<>();

        RuntimeLease runtimeLease = acquireRuntime(agentId);
        AdkAgentRuntime runtime = runtimeLease.runtime();
        Content content = confirmationContent(confirmationCallId, confirmed);

        SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                runtimeLease, userId, sessionId, content);
        try {
            // 仍使用原 Session 的 InvocationLease，避免恢复与同一会话中的新对话并发写入 Event。
            runAsync(runtime, userId, sessionId, content, runtime.runConfig())
                    .blockingForEach(event -> appendFinalResponse(
                            outputs, confirmations, emittedConfirmations, event));
            lease.success();
            return new ChatResult(outputs, confirmations);
        } catch (RuntimeException | Error error) {
            lease.fail();
            throw error;
        }
    }

    /** 创建 HITL 流式恢复调用；原请求的 SSE 已结束，本次是新的独立 ADK invocation。 */
    @Override
    public ChatStream runConfirmationStream(
            String agentId,
            String userId,
            String sessionId,
            String confirmationCallId,
            boolean confirmed) {
        // 原 SSE 已在首次请求暂停时结束；这里是独立的 SSE Runner 调用。
        Content content = confirmationContent(confirmationCallId, confirmed);

        return observer -> {
            RuntimeLease runtimeLease = acquireRuntime(agentId);
            AdkAgentRuntime runtime = runtimeLease.runtime();
            RunConfig runConfig = runtime.runConfig().toBuilder()
                    .streamingMode(RunConfig.StreamingMode.SSE)
                    .build();
            SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                    runtimeLease, userId, sessionId, content);
            try {
                Flowable<Event> events = runAsync(runtime, userId, sessionId, content, runConfig);
                AtomicBoolean partialTextEmitted = new AtomicBoolean(false);
                Set<String> emittedConfirmations = new HashSet<>();
                var disposable = events.subscribe(
                        event -> emitStreamingResponse(observer, event, partialTextEmitted, emittedConfirmations),
                        error -> {
                            lease.fail();
                            observer.onError(error);
                        },
                        () -> {
                            lease.success();
                            observer.onComplete();
                        });
                return () -> {
                    // 取消必须传递到上游，并由 lease 按失败调用清理或保留可恢复 Event。
                    disposable.dispose();
                    lease.fail();
                };
            } catch (RuntimeException | Error error) {
                lease.fail();
                throw error;
            }
        };
    }


    /**
     * 将受控 Artifact 名称写入当前轮模型输入。
     *
     * <p>这里不能读取对象存储并构造 inlineData。模型随后调用 {@code LoadArtifactsTool} 时，ADK 才会
     * 以当前 ToolContext 的完整 Session 身份临时加载二进制；该内容不会写入 Session Event。</p>
     */
    /** 将文本、外部 URI 和 Artifact 引用组装成当前 invocation 的 ADK 用户 Content。 */
    private Content commandContent(ChatCommandEntity command) {
        List<Part> parts = new ArrayList<>();
        if (command.getTexts() != null) {
            command.getTexts().forEach(text -> parts.add(Part.fromText(text.getMessage())));
        }
        if (command.getFiles() != null) {
            command.getFiles().forEach(file -> parts.add(Part.fromUri(file.getFileUri(), file.getMimeType())));
        }
        if (command.getAttachments() != null && !command.getAttachments().isEmpty()) {
            String artifactNames = command.getAttachments().stream()
                    .map(AttachmentReference::artifactName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.joining(", "));
            // 该文本可以安全持久化，仅提示模型按需调用内置工具，绝不包含 object key、URL 或原始内容。
            parts.add(Part.fromText("本次上传的附件已保存为 Artifact；需要读取内容时请调用 load_artifacts："
                    + artifactNames));
        }
        return Content.builder().role("user").parts(parts).build();
    }


    /**
     * 统一建立 ADK 响应式调用，并在错误通道中完成模型异常映射。
     *
     * <p>{@code runAsync()} 返回后发生的超时不会被普通 try/catch 捕获，必须在 Flowable 的
     * onError 通道转换；同时保留建立 Flowable 时同步抛错的防御分支。</p>
     */
    private Flowable<Event> runAsync(
            AdkAgentRuntime runtime,
            String userId,
            String sessionId,
            Content content,
            RunConfig runConfig) {
        try {
            return runtime.runner()
                    .runAsync(userId, sessionId, content, runConfig)
                    .onErrorResumeNext(error -> Flowable.error(ModelInvocationFailureMapper.map(error)));
        } catch (RuntimeException error) {
            Throwable mapped = ModelInvocationFailureMapper.map(error);
            throw mapped instanceof RuntimeException runtimeException ? runtimeException : error;
        }
    }

    /** ADK 要求恢复请求沿用确认 FunctionCall 的 id，并用保留函数名承载决策。 */
    private Content confirmationContent(String confirmationCallId, boolean confirmed) {
        // 不能改用原业务工具名：ADK 只会把 adk_request_confirmation 响应解释为 HITL 决策。
        FunctionResponse response = FunctionResponse.builder()
                .id(confirmationCallId)
                .name("adk_request_confirmation")
                .response(Map.of("confirmed", confirmed))
                .build();
        return Content.fromParts(Part.builder().functionResponse(response).build());
    }

    /** 从同步调用的终态 Event 中提取文本和去重后的确认请求。 */
    private void appendFinalResponse(
            List<String> outputs,
            List<ToolConfirmationRequest> confirmations,
            Set<String> emittedConfirmations,
            Event event) {
        findToolConfirmation(event).ifPresent(request -> {
            if (emittedConfirmations.add(request.confirmationId())) {
                confirmations.add(request);
            }
        });
        if (event.finalResponse()) {
            // finalResponse 只表示 ADK 阶段可以结束，不能把 FunctionCall/FunctionResponse
            // 或 compaction 控制 Event 当作对话文本返回给业务接口。
            if (!event.functionCalls().isEmpty()
                    || !event.functionResponses().isEmpty()
                    || (event.actions() != null && event.actions().compaction().isPresent())) {
                return;
            }
            String text = displayableText(event);
            if (!text.isBlank()) {
                outputs.add(text);
            }
        }
    }

    /**
     * 将 ADK 模型 Event 映射为客户端文本片段，并跳过已经逐片发送过的完整终态副本。
     */
    private void emitStreamingResponse(
            ChatStreamObserver observer,
            Event event,
            AtomicBoolean partialTextEmitted,
            Set<String> emittedConfirmations) {
        findToolConfirmation(event).ifPresent(request -> {
            if (emittedConfirmations.add(request.confirmationId())) {
                observer.onEvent(ChatEvent.confirmation(request));
            }
        });

        if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
            // 工具事件是模型阶段边界，但不属于当前 HTTP 对话协议。
            partialTextEmitted.set(false);
            return;
        }

        if (event.partial().orElse(false)) {
            String text = displayableText(event);
            if (!text.isEmpty()) {
                observer.onEvent(new ChatEvent(text));
                partialTextEmitted.set(true);
            }
            return;
        }

        if (event.finalResponse()) {
            // 装饰器生成的终态全文用于 Session 持久化；客户端已经收到分片时不再重复发送。
            if (!partialTextEmitted.getAndSet(false)) {
                observer.onEvent(new ChatEvent(event.stringifyContent()));
            }
        }
    }

    /** 拼接 Event 中的非 thought 文本 Part，过滤内部思考内容。 */
    private String displayableText(Event event) {
        StringBuilder text = new StringBuilder();
        event.content()
                .flatMap(Content::parts)
                .orElse(List.of())
                .stream()
                .filter(part -> !part.thought().orElse(false))
                .forEach(part -> part.text().ifPresent(text::append));
        return text.toString();
    }

    /**
     * 将 ADK 的内部确认 FunctionCall 转换为项目内可持久化、可展示的确认请求。
     *
     * <p>只识别 ADK 保留的确认函数名；原始工具参数不向客户端传播，后续恢复阶段再由
     * infrastructure 使用保存的 ADK call id 构造 FunctionResponse。</p>
     */
    private Optional<ToolConfirmationRequest> findToolConfirmation(Event event) {
        for (FunctionCall call : event.functionCalls()) {
            if (!"adk_request_confirmation".equals(call.name().orElse(null))) {
                continue;
            }
            String adkCallId = call.id().orElse(event.id());
            String invocationId = event.invocationId();
            if (adkCallId == null || adkCallId.isBlank() || invocationId == null || invocationId.isBlank()) {
                continue;
            }

            Map<String, Object> args = call.args().orElse(Map.of());
            String toolName = functionCallName(args.get("originalFunctionCall"));
            if (toolName == null || toolName.isBlank()) {
                toolName = "unknown_tool";
            }
            String message = confirmationHint(args.get("toolConfirmation"));
            if (message == null || message.isBlank()) {
                message = "工具 " + toolName + " 需要人工确认";
            }

            String confirmationId = UUID.nameUUIDFromBytes(("adk-confirmation:" + invocationId + ":" + adkCallId)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            return Optional.of(new ToolConfirmationRequest(
                    confirmationId, adkCallId, invocationId, toolName, message));
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    /** ADK 内存 Event 使用 FunctionCall 对象，数据库 JSON 回放后则可能变成 Map。 */
    /** 从内存对象或 JSON 回放后的 Map 中读取原始工具名称。 */
    private String functionCallName(Object value) {
        if (value instanceof FunctionCall functionCall) {
            return functionCall.name().orElse(null);
        }
        return stringValue(nestedMap(value).get("name"));
    }

    /** ToolConfirmation 同样需要兼容 ADK 对象和 JSON Map 两种形态。 */
    /** 从内存对象或 JSON 回放后的 Map 中读取人工确认提示。 */
    private String confirmationHint(Object value) {
        if (value instanceof ToolConfirmation toolConfirmation) {
            return toolConfirmation.hint();
        }
        return stringValue(nestedMap(value).get("hint"));
    }

    /** 只接受字符串类型的协议字段，其他值按缺失处理。 */
    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    /** 获取受 generation 保护的 Runtime；draining Runtime 不再接受新请求。 */
    private RuntimeLease acquireRuntime(String agentId) {
        return runtimeRegistry.acquire(agentId)
                .orElseThrow(() -> new AppException(ResponseCode.AGENT_NOT_FOUND));
    }
}
