package com.hh.application.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次非流式对话的项目内结果。
 *
 * <p>通过不可变集合隔离运行时适配器返回的可变 SDK 容器；调用方只能读取已经提取的助手文本。</p>
 */
public record ChatResult(List<String> contents) {

    public ChatResult {
        Objects.requireNonNull(contents, "contents must not be null");
        contents = List.copyOf(contents);
    }

    /**
     * 沿用既有 HTTP 与归档行为：多个最终响应以换行符拼接为一条可展示文本。
     */
    public String joinContents() {
        return String.join("\n", contents);
    }
}
