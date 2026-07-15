package com.hh.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("session_events")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SessionEventPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String userId;
    private String sessionId;
    private String eventId;
    private String invocationId;
    private String author;
    private Boolean finalResponse;
    private Boolean partial;
    private Boolean compactionEvent;
    private Long eventTs;
    private String contentText;
    private String eventJson;
    private LocalDateTime createdAt;

}
