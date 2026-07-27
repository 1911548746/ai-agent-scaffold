/**
 * 模块化 Agent catalog 的加载、校验、引用解析与 Definition 编译。
 *
 * <p>本包将 catalog 引用的 models、tools、agents 和 workflows 配置资源编译为完整的
 * {@code AgentDefinition}，并在创建任何运行时资源之前完成重复 ID、未知引用、工作流循环、
 * root agent 缺失及 MCP transport 冲突等检查。</p>
 *
 * <p>配置异常只允许包含资源路径、字段路径和稳定 ID，不得包含模型密钥或 MCP header。</p>
 */
package com.hh.config.agent.catalog;

