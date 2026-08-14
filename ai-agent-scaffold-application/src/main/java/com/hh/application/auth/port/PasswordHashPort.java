package com.hh.application.auth.port;

/** 密码派生和校验端口，避免 application 绑定具体加密实现。 */
public interface PasswordHashPort {

    String hash(String plainPassword);

    boolean matches(String plainPassword, String passwordHash);
}
