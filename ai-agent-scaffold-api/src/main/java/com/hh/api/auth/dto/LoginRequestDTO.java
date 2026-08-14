package com.hh.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户登录请求。 */
public record LoginRequestDTO(
        @NotBlank(message = "username不能为空") @Size(max = 64, message = "username长度不能超过64个字符") String username,
        @NotBlank(message = "password不能为空") @Size(min = 8, max = 128, message = "password长度必须在8到128个字符之间") String password) {
}
