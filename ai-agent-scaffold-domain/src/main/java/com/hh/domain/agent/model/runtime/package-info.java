/**
 * 已完成 ADK 装配的 Agent 运行时快照及其原子发布 Registry。
 *
 * <p>该包是 YAML 配置编译与请求执行之间的边界：请求只能读取已发布快照，
 * 不得通过 Spring 容器按 agentId 动态查找或注册 Bean。</p>
 */
package com.hh.domain.agent.model.runtime;
