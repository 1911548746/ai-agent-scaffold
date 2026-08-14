package com.hh.application.auth.usecase;

import com.hh.application.auth.model.AuthenticationResult;

/** 用户注册与登录的入站用例。 */
public interface AuthUseCase {

    AuthenticationResult register(String username, String password);

    AuthenticationResult login(String username, String password);
}
