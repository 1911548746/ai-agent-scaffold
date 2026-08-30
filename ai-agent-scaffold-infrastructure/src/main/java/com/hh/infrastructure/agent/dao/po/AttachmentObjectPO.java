package com.hh.infrastructure.agent.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 附件对象的受控元数据；原始二进制始终保存在对象存储中。 */
@TableName("attachment_objects")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentObjectPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String artifactName;
    private Integer artifactVersion;
    private String appName;
    private String userId;
    private String sessionId;
    private String objectKey;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
