package com.hh.infrastructure.agent.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("conversations")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ConversationPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外暴露的 UUID 标识；数据库内部关联仍使用自增 id。 */
    private String conversationId;
    private String appName;
    private String userId;
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
