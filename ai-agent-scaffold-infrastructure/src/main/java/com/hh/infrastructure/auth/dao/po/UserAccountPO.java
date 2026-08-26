package com.hh.infrastructure.auth.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** users 表的持久化对象，仅在基础设施层使用。 */
@TableName("users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String username;
    private String passwordHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
