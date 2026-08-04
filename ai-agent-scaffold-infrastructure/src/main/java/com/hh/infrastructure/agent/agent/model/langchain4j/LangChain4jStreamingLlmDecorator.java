package com.hh.infrastructure.agent.agent.model.langchain4j;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RetriableException;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 修正 ADK LangChain4j 流式响应边界并保护首响应阶段的模型装饰器。
 *
 * <p>ADK 1.7.0 的 LangChain4j 适配器没有为普通文本片段设置 {@code partial=true}，
 * 也不会在流结束时补充完整文本响应。本装饰器复用官方请求与工具映射，仅修正已经输出的
 * {@link LlmResponse}，避免 SessionStore 将每个文本片段当作终态 Event 持久化。</p>
 *
 * <p>首响应前的瞬时故障允许有限重试；任意响应一旦到达便永久关闭当前调用的重试资格，
 * 防止客户端已经收到片段后再次执行相同模型请求。</p>
 */
@Slf4j
public final class LangChain4jStreamingLlmDecorator extends BaseLlm {

    private static final String MODEL_ROLE = "model";
    private static final Duration DEFAULT_FIRST_RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    private final BaseLlm delegate;
    private final Duration firstResponseTimeout;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Scheduler scheduler;

    public LangChain4jStreamingLlmDecorator(BaseLlm delegate) {
        this(delegate, DEFAULT_FIRST_RESPONSE_TIMEOUT, DEFAULT_MAX_RETRIES);
    }

    public LangChain4jStreamingLlmDecorator(
            BaseLlm delegate,
            Duration firstResponseTimeout,
            int maxRetries) {
        this(delegate, firstResponseTimeout, maxRetries, DEFAULT_INITIAL_BACKOFF, Schedulers.computation());
    }

    LangChain4jStreamingLlmDecorator(
            BaseLlm delegate,
            Duration firstResponseTimeout,
            int maxRetries,
            Duration initialBackoff,
            Scheduler scheduler) {
        super(Objects.requireNonNull(delegate, "delegate must not be null").model());
        if (Objects.requireNonNull(firstResponseTimeout, "firstResponseTimeout must not be null").isZero()
                || firstResponseTimeout.isNegative()) {
            throw new IllegalArgumentException("firstResponseTimeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (Objects.requireNonNull(initialBackoff, "initialBackoff must not be null").isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        this.delegate = delegate;
        this.firstResponseTimeout = firstResponseTimeout;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        if (!stream) {
            return delegate.generateContent(request, false);
        }

        // 每次订阅创建独立缓冲，避免并发 invocation 或重复订阅共享响应片段。
        return Flowable.defer(() -> {
            StreamingResponseState state = new StreamingResponseState();
            return streamWithFirstResponseResilience(request)
                    .map(state::normalize)
                    // concatWith 仅在正常完成时执行；异常和取消不会生成伪终态响应。
                    .concatWith(Flowable.defer(() -> state.terminalResponse()
                            .map(Flowable::just)
                            .orElseGet(Flowable::empty)));
        });
    }

    /**
     * 只保护首个响应之前的阶段；收到任意响应后，超时计时与重试资格都立即失效。
     */
    private Flowable<LlmResponse> streamWithFirstResponseResilience(LlmRequest request) {
        return Flowable.defer(() -> {
            AtomicBoolean responseReceived = new AtomicBoolean(false);
            AtomicInteger retryCount = new AtomicInteger(0);

            return Flowable.defer(() -> delegate.generateContent(request, true)
                            .timeout(
                                    Flowable.timer(firstResponseTimeout.toMillis(), TimeUnit.MILLISECONDS, scheduler),
                                    ignored -> Flowable.never()))
                    .doOnNext(ignored -> responseReceived.set(true))
                    .retryWhen(errors -> errors.concatMap(error ->
                            retryOrFail(error, responseReceived.get(), retryCount)));
        });
    }

    private Flowable<Long> retryOrFail(Throwable error, boolean responseReceived, AtomicInteger retryCount) {
        if (responseReceived || !isRetryable(error) || retryCount.get() >= maxRetries) {
            return Flowable.error(error);
        }

        int retryNumber = retryCount.incrementAndGet();
        long backoffMillis = backoffMillis(retryNumber);
        // 不输出供应商响应或异常消息，避免错误正文携带内部地址或请求内容。
        log.warn("流式模型首响应前调用失败，准备有限重试 retry={}/{}, backoffMs={}, failureType={}",
                retryNumber, maxRetries, backoffMillis, error.getClass().getSimpleName());
        return Flowable.timer(backoffMillis, TimeUnit.MILLISECONDS, scheduler);
    }

    private long backoffMillis(int retryNumber) {
        long initialMillis = initialBackoff.toMillis();
        int shift = Math.min(retryNumber - 1, 30);
        long multiplier = 1L << shift;
        long delay = initialMillis > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : initialMillis * multiplier;
        return Math.min(delay, MAX_BACKOFF.toMillis());
    }

    private static boolean isRetryable(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (current instanceof RetriableException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            if (current instanceof HttpException httpException
                    && (httpException.statusCode() == 408
                    || httpException.statusCode() == 429
                    || httpException.statusCode() >= 500)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
