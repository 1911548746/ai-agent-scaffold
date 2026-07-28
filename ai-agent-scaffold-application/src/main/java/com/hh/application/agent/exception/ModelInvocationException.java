package com.hh.application.agent.exception;

import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 模型调用最终失败时向上层暴露的稳定异常。
 *
 * <p>该异常只保存项目定义的失败类型和公开文案，不依赖 LangChain4j、ADK 或具体模型供应商。
 * 原始异常仅作为服务端诊断 cause 保留，禁止将其 message 直接返回给客户端。</p>
 */
public final class ModelInvocationException extends AppException {

    private final FailureType failureType;

    public ModelInvocationException(FailureType failureType, Throwable cause) {
        super(failureType.responseCode().getCode(), failureType.responseCode().getInfo(), cause);
        this.failureType = failureType;
    }

    public FailureType failureType() {
        return failureType;
    }

    /**
     * 返回最深层原因的类型名，供日志定位使用；异常消息可能含供应商响应或凭据，因此不对外提供。
     */
    public String rootCauseType() {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = getCause();
        Throwable root = current;
        while (current != null && visited.add(current)) {
            root = current;
            current = current.getCause();
        }
        return root == null ? getClass().getName() : root.getClass().getName();
    }

    /**
     * 失败类型只表达 application 可识别的稳定语义，不泄漏第三方异常类型。
     */
    public enum FailureType {
        TIMEOUT(ResponseCode.MODEL_TIMEOUT),
        RATE_LIMITED(ResponseCode.MODEL_RATE_LIMITED),
        CONFIGURATION_ERROR(ResponseCode.MODEL_CONFIGURATION_ERROR),
        REQUEST_REJECTED(ResponseCode.MODEL_REQUEST_REJECTED),
        UNAVAILABLE(ResponseCode.MODEL_UNAVAILABLE);

        private final ResponseCode responseCode;

        FailureType(ResponseCode responseCode) {
            this.responseCode = responseCode;
        }

        public ResponseCode responseCode() {
            return responseCode;
        }
    }
}
