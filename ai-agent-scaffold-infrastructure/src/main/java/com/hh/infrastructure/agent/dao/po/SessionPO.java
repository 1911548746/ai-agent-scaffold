package com.hh.infrastructure.agent.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sessions")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SessionPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String userId;
    private String sessionId;
    private String stateJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
