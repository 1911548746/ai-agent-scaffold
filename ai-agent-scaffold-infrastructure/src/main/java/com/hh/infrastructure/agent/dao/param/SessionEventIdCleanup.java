package com.hh.infrastructure.agent.dao.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按完整 Session 身份精确删除指定 Event ID 的参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEventIdCleanup {

    private String appName;
    private String userId;
    private String sessionId;
    private List<String> eventIds;
}
