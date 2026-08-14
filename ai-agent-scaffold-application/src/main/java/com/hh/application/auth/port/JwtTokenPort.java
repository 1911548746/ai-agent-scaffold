package com.hh.application.auth.port;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.domain.auth.model.UserAccount;

/** 无状态访问令牌的签发和验证端口。 */
public interface JwtTokenPort {

    String issue(UserAccount userAccount);

    AuthenticatedPrincipal parse(String token);
}
