/**
 * Agent 模块化 YAML 配置的加载、绑定、校验与编译。
 *
 * <p>本包从 catalog 引用的 models、tools、agents 和 workflows 资源生成完整的
 * {@code AgentDefinition}，只负责配置层面的规则校验，不负责创建 ADK Agent、Runner 或外部连接。</p>
 *
 * <p>配置错误必须在启动装配前失败，异常信息仅可包含资源路径、字段路径和稳定 ID，
 * 不得包含模型密钥、Authorization 或 MCP header 值。</p>
 */
package com.hh.config.agent;
