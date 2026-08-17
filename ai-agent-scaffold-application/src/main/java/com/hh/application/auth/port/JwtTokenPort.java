package com.hh.application.auth.port;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.domain.auth.model.UserAccount;

/** JWT 令牌端口  */
public interface JwtTokenPort {

    /** 生成令牌  */
    String generate(UserAccount userAccount);

    /** 解析令牌  */
    AuthenticatedPrincipal parse(String token);
}
