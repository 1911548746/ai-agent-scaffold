package com.hh.infrastructure.agent.agent.model.langchain4j;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 修正 ADK LangChain4j 流式响应边界的模型装饰器。
 *
 * <p>ADK 1.7.0 的 LangChain4j 适配器没有为普通文本片段设置 {@code partial=true}，
 * 也不会在流结束时补充完整文本响应。本装饰器复用官方请求与工具映射，仅修正已经输出的
 * {@link LlmResponse}，避免 SessionStore 将每个文本片段当作终态 Event 持久化。</p>
 */
public final class LangChain4jStreamingLlmDecorator extends BaseLlm {

    private static final String MODEL_ROLE = "model";

    private final BaseLlm delegate;

    public LangChain4jStreamingLlmDecorator(BaseLlm delegate) {
        super(Objects.requireNonNull(delegate, "delegate must not be null").model());
        this.delegate = delegate;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        if (!stream) {
            return delegate.generateContent(request, false);
        }

        // 每次订阅创建独立缓冲，避免并发 invocation 或重复订阅共享响应片段。
        return Flowable.defer(() -> {
            StreamingResponseState state = new StreamingResponseState();
            return delegate.generateContent(request, true)
                    .map(state::normalize)
                    // concatWith 仅在正常完成时执行；异常和取消不会生成伪终态响应。
                    .concatWith(Flowable.defer(() -> state.terminalResponse()
                            .map(Flowable::just)
                            .orElseGet(Flowable::empty)));
        });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        return delegate.connect(request);
    }

    /** 维护单次模型流的文本与终态边界，不向装饰器实例泄漏可变状态。 */
    private static final class StreamingResponseState {

        private final StringBuilder text = new StringBuilder();
        private LlmResponse lastTextResponse;
        private boolean textReceived;
        private boolean explicitTerminalReceived;
        private boolean controlResponseReceived;

        private LlmResponse normalize(LlmResponse response) {
            Objects.requireNonNull(response, "response must not be null");
            LlmResponse normalized = normalizeModelRole(response);
            boolean textOnly = isTextOnly(normalized);

            if (normalized.partial().isPresent()) {
                if (normalized.partial().orElse(false)) {
                    if (textOnly) {
                        appendText(normalized);
                    } else {
                        // 非文本 partial 无法安全聚合，保持上游语义且禁止补造文本终态。
                        controlResponseReceived = true;
                    }
                } else {
                    // 上游已提供明确终态时直接复用，兼容未来 ADK 修复后的实现。
                    explicitTerminalReceived = true;
                }
                return normalized;
            }

            if (!textOnly) {
                // FunctionCall、错误或其他控制响应继续交给 ADK 原流程处理。
                controlResponseReceived = true;
                return normalized;
            }

            appendText(normalized);
            return normalized.toBuilder().partial(true).build();
        }

        private Optional<LlmResponse> terminalResponse() {
            if (!textReceived || explicitTerminalReceived || controlResponseReceived) {
                return Optional.empty();
            }

            Content completedContent = Content.builder()
                    .role(MODEL_ROLE)
                    .parts(List.of(Part.fromText(text.toString())))
                    .build();
            return Optional.of(lastTextResponse.toBuilder()
                    .content(completedContent)
                    .partial(false)
                    .build());
        }

        private void appendText(LlmResponse response) {
            int originalLength = text.length();
            response.content()
                    .flatMap(Content::parts)
                    .orElse(List.of())
                    .forEach(part -> part.text().ifPresent(text::append));
            if (text.length() > originalLength) {
                lastTextResponse = response;
                textReceived = true;
            }
        }

        private static LlmResponse normalizeModelRole(LlmResponse response) {
            Optional<Content> content = response.content();
            if (content.isEmpty() || content.get().role().filter(role -> !role.isBlank()).isPresent()) {
                return response;
            }
            return response.toBuilder()
                    .content(content.get().toBuilder().role(MODEL_ROLE).build())
                    .build();
        }

        private static boolean isTextOnly(LlmResponse response) {
            List<Part> parts = response.content()
                    .flatMap(Content::parts)
                    .orElse(List.of());
            return !parts.isEmpty() && parts.stream().allMatch(part ->
                    part.text().isPresent() && !part.thought().orElse(false));
        }
    }
}
