package com.hh.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Session Event 读取参数。
 *
 * <p>查询对象与表 PO 分离，避免为承载 {@code limit}、时间边界等非持久化字段污染实体映射。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEventQuery {

    private String appName;
    private String userId;
    private String sessionId;
    private Integer limit;
    private Long afterTimestamp;
}
