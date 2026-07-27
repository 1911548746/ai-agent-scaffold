/**
 * Agent 运行时的启动编排与原子发布入口。
 *
 * <p>本包在 Spring 单例完成初始化后加载已经校验的 Agent 定义，并委托 application 端口
 * 构建全部候选运行时；只有所有候选对象均构建成功时，才允许一次性发布新的 Registry 快照。</p>
 *
 * <p>本包不解析具体 YAML 字段，也不直接创建模型、MCP 客户端或 ADK Runner。</p>
 */
package com.hh.config.agent.bootstrap;

