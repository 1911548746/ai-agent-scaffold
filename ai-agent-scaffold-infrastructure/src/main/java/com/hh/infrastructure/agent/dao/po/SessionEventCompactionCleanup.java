package com.hh.infrastructure.agent.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 已成功写入摘要后可安全删除的源 Event 集合。
 *
 * <p>eventIds 来自 ADK 本轮传给 summarizer 的精确输入，不使用行 ID 或秒级时间戳推断，避免误删
 * sliding-window overlap Event。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEventCompactionCleanup {

    private String appName;
    private String userId;
    private String sessionId;
    private String summaryEventId;
    private List<String> eventIds;
}
