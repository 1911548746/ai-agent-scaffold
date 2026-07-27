package com.hh.infrastructure.agent.agent.session;

import com.google.adk.sessions.BaseSessionService;

/**
 * ADK 会话与事件持久化扩展点。
 *
 * <p>BaseSessionService 是 Google ADK 的扩展点，因此接口必须位于 infrastructure；MySQL
 * 等具体存储实现只在此处适配，不能将 SDK 类型重新传回 domain。</p>
 */
public interface AdkSessionStore extends BaseSessionService {
}
