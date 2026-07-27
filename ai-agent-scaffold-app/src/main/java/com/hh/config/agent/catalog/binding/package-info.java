/**
 * 单个 Agent 模块 YAML 文件的 Spring 绑定模型。
 *
 * <p>每个类型只描述 models、tools、agents 或 workflows 中的一种领域对象，保留稳定 ID
 * 和引用字段，不负责跨文件引用解析，也不持有 ADK、LangChain4j 等运行时对象。</p>
 *
 * <p>这些对象是配置输入模型，不应作为领域模型或对外 API 契约使用。</p>
 */
package com.hh.config.agent.catalog.binding;

