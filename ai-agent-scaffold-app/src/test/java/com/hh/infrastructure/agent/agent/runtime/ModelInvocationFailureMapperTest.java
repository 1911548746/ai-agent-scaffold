package com.hh.infrastructure.agent.agent.runtime;

import com.hh.application.agent.exception.ModelInvocationException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证模型异常分类完全离线，不建立 HTTP 连接或调用真实供应商。
 */
class ModelInvocationFailureMapperTest {

    @Test
    void classifiesKnownModelFailuresWithoutExposingProviderMessage() {
        assertFailure(new TimeoutException(new HttpTimeoutException("apiKey=secret")),
                ModelInvocationException.FailureType.TIMEOUT);
        assertFailure(new RateLimitException("provider quota response"),
                ModelInvocationException.FailureType.RATE_LIMITED);
        assertFailure(new AuthenticationException("invalid credential"),
                ModelInvocationException.FailureType.CONFIGURATION_ERROR);
        assertFailure(new HttpException(503, "provider unavailable"),
                ModelInvocationException.FailureType.UNAVAILABLE);
    }

    @Test
    void keepsNonModelFailureUnchanged() {
        IllegalStateException toolOrSessionFailure = new IllegalStateException("not a model failure");

        assertThat(ModelInvocationFailureMapper.map(toolOrSessionFailure))
                .isSameAs(toolOrSessionFailure);
    }

    private void assertFailure(Throwable error, ModelInvocationException.FailureType expectedType) {
        assertThat(ModelInvocationFailureMapper.map(error))
                .isInstanceOfSatisfying(ModelInvocationException.class, mapped -> {
                    assertThat(mapped.failureType()).isEqualTo(expectedType);
                    assertThat(mapped.getInfo()).isEqualTo(expectedType.responseCode().getInfo());
                    assertThat(mapped.getInfo()).doesNotContain(error.getMessage());
                    assertThat(mapped.getCause()).isSameAs(error);
                });
    }
}
