package com.hh.trigger.http.auth;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.types.exception.AuthenticationException;

/**
 * 仅供同步 Controller 调用栈读取的认证主体上下文。
 *
 * <p>SSE 异步回调不得读取该 ThreadLocal，Controller 必须在建流前显式把 userId 传给 application。
 * 切面会在方法退出后清理，避免 Tomcat 工作线程复用时发生身份串扰。</p>
 */
public final class AuthenticatedPrincipalContext {

    private static final ThreadLocal<AuthenticatedPrincipal> CURRENT = new ThreadLocal<>();

    private AuthenticatedPrincipalContext() {
    }

    public static void set(AuthenticatedPrincipal principal) {
        CURRENT.set(principal);
    }

    public static AuthenticatedPrincipal requireCurrent() {
        AuthenticatedPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new AuthenticationException();
        }
        return principal;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
