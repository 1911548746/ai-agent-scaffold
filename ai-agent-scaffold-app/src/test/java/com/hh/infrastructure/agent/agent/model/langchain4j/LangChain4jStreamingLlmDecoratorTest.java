package com.hh.infrastructure.agent.agent.model.langchain4j;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import dev.langchain4j.exception.RetriableException;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 LangChain4j 流式边界修正逻辑；所有模型输出均由内存 Stub 提供。
 */
class LangChain4jStreamingLlmDecoratorTest {

    private final LlmRequest request = mock(LlmRequest.class);

    @Test
    void marksBrokenTextChunksPartialAndAppendsCompletedResponse() {
        BaseLlm decorator = decorator(stream -> Flowable.just(text("你", null), text("好", null)));

        List<LlmResponse> responses = decorator.generateContent(request, true).toList().blockingGet();

        assertThat(responses).hasSize(3);
        assertThat(responses.subList(0, 2))
                .allSatisfy(response -> {
                    assertThat(response.partial()).contains(true);
                    assertThat(response.content().orElseThrow().role()).contains("model");
                });
        assertThat(responses.get(2).partial()).contains(false);
        assertThat(responses.get(2).content().orElseThrow().text()).isEqualTo("你好");
    }

    @Test
    void preservesAlreadyCorrectStreamingBoundariesWithoutDuplicateTerminal() {
        BaseLlm decorator = decorator(stream -> Flowable.just(
                text("你", true),
                text("你好", false)));

        List<LlmResponse> responses = decorator.generateContent(request, true).toList().blockingGet();

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(response -> response.partial().orElse(null))
                .containsExactly(true, false);
        assertThat(responses.get(1).content().orElseThrow().text()).isEqualTo("你好");
    }

    @Test
    void delegatesNonStreamingAndKeepsToolResponseUnchanged() {
        LlmResponse syncResponse = text("同步回复", false);
        BaseLlm syncDecorator = decorator(stream -> Flowable.just(syncResponse));

        assertThat(syncDecorator.generateContent(request, false).blockingFirst()).isSameAs(syncResponse);

        LlmResponse toolResponse = LlmResponse.builder()
                .content(Content.builder()
                        .parts(List.of(Part.fromFunctionCall("lookup", Map.of("city", "Shanghai"))))
                        .build())
                .build();
        BaseLlm toolDecorator = decorator(stream -> Flowable.just(toolResponse));

        List<LlmResponse> responses = toolDecorator.generateContent(request, true).toList().blockingGet();
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).partial()).isEmpty();
        assertThat(responses.get(0).content().orElseThrow().role()).contains("model");
        assertThat(responses.get(0).content().orElseThrow().parts().orElseThrow().get(0).functionCall())
                .isPresent();
    }

    @Test
    void errorEmptyAndCancellationDoNotCreateTerminalResponse() {
        BaseLlm emptyDecorator = decorator(stream -> Flowable.empty());
        emptyDecorator.generateContent(request, true).test().assertComplete().assertNoValues();

        BaseLlm emptyTextDecorator = decorator(stream -> Flowable.just(text("", null)));
        emptyTextDecorator.generateContent(request, true).test()
                .assertComplete()
                .assertValueCount(1)
                .assertValue(response -> response.partial().orElse(false));

        BaseLlm errorDecorator = decorator(stream -> Flowable.error(new IllegalStateException("failed")));
        errorDecorator.generateContent(request, true).test()
                .assertFailure(IllegalStateException.class)
                .assertNoValues();

        AtomicBoolean cancelled = new AtomicBoolean(false);
        BaseLlm neverDecorator = decorator(stream -> Flowable.<LlmResponse>never()
                .doOnCancel(() -> cancelled.set(true)));
        var subscriber = neverDecorator.generateContent(request, true).test();
        subscriber.cancel();

        assertThat(cancelled).isTrue();
    }

    @Test
    void keepsTextBufferIsolatedForEverySubscription() {
        BaseLlm decorator = decorator(stream -> Flowable.just(text("A", null), text("B", null)));
        Flowable<LlmResponse> stream = decorator.generateContent(request, true);

        List<LlmResponse> first = stream.toList().blockingGet();
        List<LlmResponse> second = stream.toList().blockingGet();

        assertThat(first.get(2).content().orElseThrow().text()).isEqualTo("AB");
        assertThat(second.get(2).content().orElseThrow().text()).isEqualTo("AB");
    }

    @Test
    void retriesRetriableFailureOnlyBeforeFirstResponse() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        BaseLlm decorator = decorator(stream -> attempts.incrementAndGet() == 1
                        ? Flowable.error(new RetriableException("temporary unavailable"))
                        : Flowable.just(text("恢复", null)),
                Duration.ofSeconds(1), 2, Duration.ofMillis(100), scheduler);

        var subscriber = decorator.generateContent(request, true).test();
        subscriber.assertNoValues().assertNotComplete();

        scheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS);

        subscriber.assertComplete().assertValueCount(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void firstResponseTimeoutCancelsAttemptAndThenRetries() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean firstAttemptCancelled = new AtomicBoolean(false);
        BaseLlm decorator = decorator(stream -> attempts.incrementAndGet() == 1
                        ? Flowable.<LlmResponse>never().doOnCancel(() -> firstAttemptCancelled.set(true))
                        : Flowable.just(text("重试成功", null)),
                Duration.ofSeconds(1), 1, Duration.ofMillis(100), scheduler);

        var subscriber = decorator.generateContent(request, true).test();
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        assertThat(firstAttemptCancelled).isTrue();
        subscriber.assertNoValues().assertNotComplete();

        scheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        subscriber.assertComplete().assertValueCount(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void neverRetriesAfterAnyResponseWasEmitted() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        BaseLlm decorator = decorator(stream -> {
                    attempts.incrementAndGet();
                    return Flowable.concatArray(
                            Flowable.just(text("已输出", null)),
                            Flowable.error(new RetriableException("stream interrupted")));
                },
                Duration.ofSeconds(1), 2, Duration.ofMillis(100), scheduler);

        decorator.generateContent(request, true).test()
                .assertError(RetriableException.class)
                .assertValueCount(1);
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void stopsAfterConfiguredRetriesAndDoesNotRetryPermanentFailure() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger retriableAttempts = new AtomicInteger();
        BaseLlm retriableDecorator = decorator(stream -> {
                    retriableAttempts.incrementAndGet();
                    return Flowable.error(new RetriableException("still unavailable"));
                },
                Duration.ofSeconds(1), 2, Duration.ofMillis(100), scheduler);

        var exhausted = retriableDecorator.generateContent(request, true).test();
        scheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        scheduler.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        exhausted.assertFailure(RetriableException.class).assertNoValues();
        assertThat(retriableAttempts).hasValue(3);

        AtomicInteger permanentAttempts = new AtomicInteger();
        BaseLlm permanentDecorator = decorator(stream -> {
                    permanentAttempts.incrementAndGet();
                    return Flowable.error(new IllegalArgumentException("invalid request"));
                },
                Duration.ofSeconds(1), 2, Duration.ofMillis(100), scheduler);

        permanentDecorator.generateContent(request, true).test()
                .assertFailure(IllegalArgumentException.class)
                .assertNoValues();
        assertThat(permanentAttempts).hasValue(1);
    }

    @Test
    void cancellationDuringBackoffPreventsAnotherAttempt() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        BaseLlm decorator = decorator(stream -> {
                    attempts.incrementAndGet();
                    return Flowable.error(new RetriableException("temporary unavailable"));
                },
                Duration.ofSeconds(1), 2, Duration.ofMillis(100), scheduler);

        var subscriber = decorator.generateContent(request, true).test();
        subscriber.cancel();
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        assertThat(attempts).hasValue(1);
    }

    private BaseLlm decorator(Function<Boolean, Flowable<LlmResponse>> responses) {
        return new LangChain4jStreamingLlmDecorator(new StubLlm(responses));
    }

    private BaseLlm decorator(
            Function<Boolean, Flowable<LlmResponse>> responses,
            Duration firstResponseTimeout,
            int maxRetries,
            Duration initialBackoff,
            TestScheduler scheduler) {
        return new LangChain4jStreamingLlmDecorator(
                new StubLlm(responses),
                firstResponseTimeout,
                maxRetries,
                initialBackoff,
                scheduler);
    }

    private LlmResponse text(String value, Boolean partial) {
        LlmResponse.Builder builder = LlmResponse.builder()
                .content(Content.builder().parts(List.of(Part.fromText(value))).build());
        if (partial != null) {
            builder.partial(partial);
        }
        return builder.build();
    }

    private static final class StubLlm extends BaseLlm {

        private final Function<Boolean, Flowable<LlmResponse>> responses;

        private StubLlm(Function<Boolean, Flowable<LlmResponse>> responses) {
            super("stub-model");
            this.responses = responses;
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            return responses.apply(stream);
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException("live connection is not used by this test");
        }
    }
}
