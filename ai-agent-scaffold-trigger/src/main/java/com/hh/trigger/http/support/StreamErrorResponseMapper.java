package com.hh.trigger.http.support;

import com.hh.api.agent.dto.StreamErrorResponseDTO;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.support.SensitiveDataRedactor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 将 SSE 执行链中的异常收敛为项目公开错误协议。
 *
 * <p>流建立后 HTTP 状态与响应体已经提交，Controller 只能发送 SSE error 事件。这里仅信任
 * {@link ResponseCode} 中定义的稳定错误码；SDK、供应商和工具异常的原始 message 一律不能进入
 * SSE 响应。</p>
 */
@Slf4j
public final class StreamErrorResponseMapper {

    private StreamErrorResponseMapper() {
    }

    /**
     * 将直接异常或异步包装后的 cause 链映射为公开 SSE 错误对象。
     *
     * <p>RxJava、CompletableFuture 等异步边界可能额外包装原始异常，因此不能只检查最外层类型。
     * 遍历时使用 identity 集合防御异常 cause 链意外成环。</p>
     */
    public static StreamErrorResponseDTO map(Throwable error) {
        Throwable classifiedError = findClassifiedError(error);
        if (classifiedError instanceof ModelInvocationException modelError) {
            return StreamErrorResponseDTO.knownError(modelError.failureType().responseCode());
        }
        if (classifiedError instanceof ContextCompactionException) {
            return StreamErrorResponseDTO.knownError(ResponseCode.CONTEXT_COMPACTION_UNAVAILABLE);
        }
        if (classifiedError instanceof AppException appException) {
            return knownApplicationError(appException);
        }
        return StreamErrorResponseDTO.internalError();
    }

    /**
     * 统一记录服务端诊断信息，响应仍由 {@link #map(Throwable)} 保持脱敏。
     */
    public static void logFailure(String operation, Throwable error) {
        Throwable classifiedError = findClassifiedError(error);
        if (classifiedError instanceof ModelInvocationException modelError) {
            // 模型异常只记录分类与根因类型，避免供应商响应和凭据进入日志。
            log.error("{}最终失败，失败类型: {}，根因类型: {}",
                    operation, modelError.failureType(), modelError.rootCauseType());
            return;
        }
        if (classifiedError instanceof ContextCompactionException compactionError) {
            log.error("{}失败，根因类型: {}", operation, compactionError.rootCauseType());
            return;
        }
        if (classifiedError instanceof AppException appException
                && isBuiltInResponseCode(appException.getCode())) {
            // 业务码本身可用于定位预期分支，无需打印可能夹带输入数据的异常信息。
            log.warn("{}失败，业务码: {}，异常类型: {}",
                    operation, appException.getCode(), classifiedError.getClass().getName());
            return;
        }
        // 未知异常保留脱敏堆栈，供工具、Session 与程序缺陷排查。
        log.error("{}失败，异常类型: {}，异常详情: {}",
                operation,
                error == null ? "null" : error.getClass().getName(),
                SensitiveDataRedactor.formatStackTrace(error));
    }

    private static StreamErrorResponseDTO knownApplicationError(AppException appException) {
        for (ResponseCode responseCode : ResponseCode.values()) {
            if (responseCode.getCode().equals(appException.getCode())) {
                return StreamErrorResponseDTO.knownError(responseCode);
            }
        }
        // AppException 的 info 可由业务代码拼接，未登记的 code 不能绕过公开协议白名单。
        return StreamErrorResponseDTO.internalError();
    }

    private static boolean isBuiltInResponseCode(String code) {
        for (ResponseCode responseCode : ResponseCode.values()) {
            if (responseCode.getCode().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable findClassifiedError(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        // 先查找更具体的模型/压缩异常，避免外层通用 AppException 把稳定分类遮住。
        while (current != null && visited.add(current)) {
            if (current instanceof ModelInvocationException
                    || current instanceof ContextCompactionException) {
                return current;
            }
            current = current.getCause();
        }
        visited.clear();
        current = error;
        // 只有不存在更具体异常时，才接受已登记错误码的 AppException。
        while (current != null && visited.add(current)) {
            if (current instanceof AppException) {
                return current;
            }
            current = current.getCause();
        }
        return error;
    }
}
