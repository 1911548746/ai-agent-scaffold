package com.hh.domain.agent.model.definition;

import lombok.Data;

/**
 * 已完成配置校验的本地 FunctionTool 定义。
 *
 * <p>本地工具直接引用 Spring Bean 方法。</p>
 */
@Data
public class LocalToolDefinition {

    private String beanName;
    private String methodName;

}
