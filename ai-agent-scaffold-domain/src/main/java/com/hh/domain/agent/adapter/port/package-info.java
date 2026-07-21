/**
 * 外部接口适配器层；当需要调用外部接口时，则创建出这一层，并定义接口，之后由基础设施层的 adapter 层具体实现
 */
/**
 * 领域定义的出站端口。
 *
 * <p>端口只描述调用方需要的能力，不能以 MySQL、Redis 等基础设施技术命名；具体适配器由
 * infrastructure 模块提供。</p>
 */
package com.hh.domain.agent.adapter.port;
