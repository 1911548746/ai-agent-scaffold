package com.hh.infrastructure.agent.agent.runtime.compaction;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventCompactor;
import com.google.genai.types.Content;
import com.hh.types.enums.ContextCompressTypeEnum;
import io.reactivex.rxjava3.core.Completable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 已编译到 Runtime 的上下文维护计划。
 *
 * <p>计划保留在 infrastructure，因为其执行器直接使用 ADK 的 EventCompactor。它不再注册给
 * App，避免 ADK 将压缩串到主对话 Flowable 的尾部。</p>
 */
public record RuntimeCompactionPlan(
        ContextCompressTypeEnum type,
        EventCompactor compactor,
        int tokenThreshold) {

    public RuntimeCompactionPlan {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(compactor, "compactor must not be null");
    }

    /**
     * 判断固定大小策略是否需要在本轮模型调用前压缩已有历史。
     *
     * <p>当前用户消息尚未写入 Session，因此没有可靠的 prompt token usage 时，需要把它临时计入
     * 字符估算；滑动窗口策略不在调用前执行压缩。</p>
     */
    public boolean preflightRequired(Session session, Content pendingUserMessage) {
        if (type != ContextCompressTypeEnum.FIXED_SIZE) {
            return false;
        }

        // ADK 优先信任供应商返回的 promptTokenCount；只有整段历史都没有 usage 时才使用字符估算。
        Optional<Integer> latestPromptTokenCount = latestPromptTokenCount(session.events());
        if (latestPromptTokenCount.isPresent()) {
            return latestPromptTokenCount.get() > tokenThreshold;
        }
        return estimateTokens(session.events(), pendingUserMessage) > tokenThreshold;
    }

    /** 判断主回复成功后是否需要异步执行一次窗口维护。 */
    public boolean postResponseRequired() {
        return type == ContextCompressTypeEnum.SLIDING_WINDOW
                || type == ContextCompressTypeEnum.PURE_SLIDING_WINDOW;
    }

    /** 将已装配的 ADK compactor 应用于指定 Session；事务持久化由 SessionService 自己负责。 */
    public Completable compact(Session session, BaseSessionService sessionService) {
        return compactor.compact(session, sessionService);
    }

    /** 在缺少供应商 usage metadata 时，用 ADK Event 文本长度做保守的 Token 估算。 */
    private long estimateTokens(Iterable<Event> events, Content pendingUserMessage) {
        long characters = 0;
        for (Event event : events) {
            String content = event.stringifyContent();
            characters += content == null ? 0 : content.length();
        }
        // 待发送消息尚未写入 Session，临时包装为 Event 后复用 ADK 对 Event 内容的估算口径。
        String pendingContent = Event.builder().content(pendingUserMessage).build().stringifyContent();
        characters += pendingContent == null ? 0 : pendingContent.length();
        return characters / 4;
    }

    /**
     * 从后向前取得最近一次模型调用返回的 prompt token 数，与 ADK 1.7 TailRetention 保持一致。
     */
    private Optional<Integer> latestPromptTokenCount(List<Event> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Optional<Integer> promptTokenCount = events.get(index)
                    .usageMetadata()
                    .flatMap(metadata -> metadata.promptTokenCount());
            if (promptTokenCount.isPresent()) {
                return promptTokenCount;
            }
        }
        return Optional.empty();
    }
}
