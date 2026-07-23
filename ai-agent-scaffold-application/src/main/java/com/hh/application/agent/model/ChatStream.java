package com.hh.application.agent.model;

/**
 * 对话输出流的项目内抽象。
 *
 * <p>该接口以回调和显式取消句柄表达流式能力，避免把 RxJava {@code Flowable} 作为 application
 * 对外契约。每个实例代表一次调用，只允许订阅一次；重放或多播应由明确的产品能力另行建模。</p>
 */
@FunctionalInterface
public interface ChatStream {

    /**
     * 开始接收本次调用的事件，并返回能将取消信号传回运行时适配器的句柄。
     */
    ChatSubscription subscribe(ChatStreamObserver observer);
}
