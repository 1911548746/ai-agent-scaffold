package com.hh.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {
    private Long id;
    /** 对外业务标识，避免将自增物理主键序列化给客户端。 */
    private String conversationId;
    private String appName;
    private String userId;
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
