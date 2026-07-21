package com.hh.domain.agent.adapter.port;

import com.google.adk.sessions.BaseSessionService;

/**
 * Agent 会话与事件的持久化端口。
 *
 * <p>端口名称表达所需能力，不暴露 MySQL 等具体存储技术。当前为了兼容 ADK Runner 暂继承
 * {@link BaseSessionService}；ARC-05 会将该 SDK 类型收敛到 infrastructure runtime adapter。</p>
 */
public interface SessionStore extends BaseSessionService {
}
