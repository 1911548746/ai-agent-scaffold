/**
 * Agent application 层为完成用例而声明的外部能力契约，即出站端口。
 *
 * <p>application service 只调用本包接口，不依赖 ADK Runtime、MySQL 或其他具体实现；
 * infrastructure 负责实现这些端口，并在 app 组合根中装配给相应用例。由 application
 * 定义契约可以确保依赖始终从外层适配器指向应用核心。</p>
 *
 * <p>端口方法应使用 application 或 domain 的稳定类型，不得泄漏 SDK 对象、Mapper、PO
 * 或数据库实现细节。只有真实存在的外部替换点才应定义为端口，application 内部辅助逻辑
 * 不属于本包职责。</p>
 */
package com.hh.application.agent.port;
