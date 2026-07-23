package com.hh.application.agent.model;

/**
 * 对话流的取消句柄。
 *
 * <p>取消必须幂等。trigger 在 SSE 超时、断连或写入失败时调用该方法，infrastructure 再将其映射为
 * 具体响应式或 SDK 调用的取消操作。</p>
 */
@FunctionalInterface
public interface ChatSubscription {

    void cancel();
}
