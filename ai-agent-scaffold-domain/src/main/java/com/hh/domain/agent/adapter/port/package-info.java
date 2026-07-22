/**
 * 领域定义的出站端口。
 *
 * <p>端口只描述调用方需要的能力，不能以 MySQL、Redis 等基础设施技术命名；具体适配器由
 * infrastructure 模块提供。ADK SessionService 属于 SDK 扩展点，已随 ARC-02 下沉到
 * infrastructure runtime。</p>
 */
package com.hh.domain.agent.adapter.port;
