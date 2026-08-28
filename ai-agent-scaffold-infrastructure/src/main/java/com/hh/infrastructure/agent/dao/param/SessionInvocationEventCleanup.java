package com.hh.infrastructure.agent.dao.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按 Session 身份和 invocationId 精确删除失败调用 Event 的参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionInvocationEventCleanup {

    private String appName;
    private String userId;
    private String sessionId;
    private List<String> invocationIds;
}
