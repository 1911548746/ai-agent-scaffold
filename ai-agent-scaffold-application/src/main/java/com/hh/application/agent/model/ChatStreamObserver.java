package com.hh.application.agent.model;

/**
 * 对话流的终端无关回调。
 *
 * <p>错误对象只供 trigger 记录诊断日志；HTTP 响应仍必须转换为稳定且不含供应商细节的错误体。</p>
 */
public interface ChatStreamObserver {

    void onEvent(ChatEvent event);

    void onError(Throwable error);

    void onComplete();
}
