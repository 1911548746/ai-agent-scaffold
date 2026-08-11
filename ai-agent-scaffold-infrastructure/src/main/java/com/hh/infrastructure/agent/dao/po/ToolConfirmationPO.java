package com.hh.infrastructure.agent.dao.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("tool_confirmations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolConfirmationPO {

    @TableId
    private String confirmationId;
    private Long conversationId;
    private String appName;
    private String userId;
    private String sessionId;
    private String invocationId;
    private String adkCallId;
    private String toolName;
    private String requestMessage;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
