/**
 * 不同 ADK 压缩模式对应的 Event 清理策略。
 *
 * <p>新增压缩模式时在此扩展策略，并通过注册表按稳定的配置 type 解析；未知 type 按当前 Runner
 * 约定回退为 fixedSize。</p>
 */
package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy;
