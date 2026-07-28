package com.hh.infrastructure.agent.agent.runtime.invocation;

import com.hh.application.agent.exception.ModelInvocationException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.hh.application.agent.exception.ModelInvocationException.FailureType.CONFIGURATION_ERROR;
import static com.hh.application.agent.exception.ModelInvocationException.FailureType.RATE_LIMITED;
import static com.hh.application.agent.exception.ModelInvocationException.FailureType.REQUEST_REJECTED;
import static com.hh.application.agent.exception.ModelInvocationException.FailureType.TIMEOUT;
import static com.hh.application.agent.exception.ModelInvocationException.FailureType.UNAVAILABLE;

/**
 * 将模型 SDK 异常转换为 application 可识别的稳定失败语义。
 *
 * <p>只转换明确属于模型请求的异常。工具、Plugin、Session 或数据库异常必须保持原样，
 * 否则上层会把基础设施故障误报为模型不可用。</p>
 */
public final class ModelInvocationFailureMapper {

    private ModelInvocationFailureMapper() {
    }

    static Throwable map(Throwable error) {
        if (error instanceof ModelInvocationException) {
            return error;
        }

        ModelInvocationException.FailureType failureType = classify(error);
        return failureType == null ? error : new ModelInvocationException(failureType, error);
    }

    private static ModelInvocationException.FailureType classify(Throwable error) {
        // 先匹配原因链中更具体的异常，避免外层通用 5xx 包装覆盖真实的超时或限流原因。
        if (contains(error, TimeoutException.class)
                || contains(error, HttpTimeoutException.class)
                || contains(error, SocketTimeoutException.class)
                || contains(error, java.util.concurrent.TimeoutException.class)) {
            return TIMEOUT;
        }
        if (contains(error, RateLimitException.class)) {
            return RATE_LIMITED;
        }
        if (contains(error, AuthenticationException.class)
                || contains(error, ModelNotFoundException.class)
                || contains(error, UnsupportedFeatureException.class)) {
            return CONFIGURATION_ERROR;
        }
        if (contains(error, InvalidRequestException.class)
                || contains(error, ContentFilteredException.class)) {
            return REQUEST_REJECTED;
        }
        if (contains(error, InternalServerException.class)
                || contains(error, UnresolvedModelServerException.class)) {
            return UNAVAILABLE;
        }

        HttpException httpException = find(error, HttpException.class);
        if (httpException != null) {
            return classifyHttpStatus(httpException.statusCode());
        }
        return null;
    }

    private static ModelInvocationException.FailureType classifyHttpStatus(int statusCode) {
        if (statusCode == 408 || statusCode == 504) {
            return TIMEOUT;
        }
        if (statusCode == 429) {
            return RATE_LIMITED;
        }
        if (statusCode == 401 || statusCode == 403 || statusCode == 404) {
            return CONFIGURATION_ERROR;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return REQUEST_REJECTED;
        }
        if (statusCode >= 500) {
            return UNAVAILABLE;
        }
        return null;
    }

    private static boolean contains(Throwable error, Class<? extends Throwable> type) {
        return find(error, type) != null;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
