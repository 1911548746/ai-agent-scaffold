package com.hh.types.enums;

/**
 * 应用的入站运行模式。
 *
 * <p>模式只在应用启动时确定：线上模式启用 JWT 认证，本地模式使用固定的本地用户主体。</p>
 */
public enum ApplicationMode {
    LOCAL,
    ONLINE
}
