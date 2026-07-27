/**
 * Agent application 层向入站适配器提供的用例契约，即入站端口。
 *
 * <p>HTTP Controller、Job 和消息 Listener 等入站适配器通过本包发起系统允许的业务操作，
 * application service 负责实现这些接口并编排领域模型与出站端口。调用方只依赖用例契约，
 * 不感知具体实现及其基础设施依赖。</p>
 *
 * <p>本包使用项目自有的命令、结果和流式类型描述应用能力，不得引入 HTTP DTO、
 * {@code SseEmitter}、ADK、RxJava 或数据库类型。仅供 application 内部协作的辅助服务
 * 不应为了形式统一而机械提取为 UseCase 接口。</p>
 */
package com.hh.application.agent.usecase;

