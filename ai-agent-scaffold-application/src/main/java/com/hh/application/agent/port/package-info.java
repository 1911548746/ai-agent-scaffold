/**
 * application 用例对外部能力的依赖契约。
 *
 * <p>运行时执行、对话归档和消息归档均由本包定义所需能力，infrastructure 负责适配 ADK、
 * MySQL 等具体技术；application 只依赖这些稳定接口。</p>
 */
package com.hh.application.agent.port;
