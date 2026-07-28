package com.hh.application.agent.exception;

import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

/**
 * 上下文压缩作为调用前置条件失败时的稳定异常。
 *
 * <p>它与主模型调用失败必须区分：fixedSize 在此异常发生时尚未开始本轮模型生成，
 * 调用方可以安全重试，而不会把失败错误解释为“模型已经回答失败”。</p>
 */
public final class ContextCompactionException extends AppException {

    public ContextCompactionException(Throwable cause) {
        super(ResponseCode.CONTEXT_COMPACTION_UNAVAILABLE.getCode(),
                ResponseCode.CONTEXT_COMPACTION_UNAVAILABLE.getInfo(), cause);
    }

    /** 只暴露原因类型供服务端日志定位，避免供应商异常消息进入公开响应。 */
    public String rootCauseType() {
        Throwable root = getCause();
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? getClass().getName() : root.getClass().getName();
    }
}
