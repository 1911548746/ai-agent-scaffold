package com.hh.trigger.http.auth;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.types.enums.ApplicationMode;
import com.hh.types.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.Optional;

/**
 * HTTP 入站认证拦截器：按启动模式验证 Bearer JWT 或建立本地固定用户主体。
 *
 * <p>认证属于 HTTP 协议边界，使用 MVC 拦截器可以在 Controller 之前直接拒绝请求，
 * 而不是让业务切面承担 Header 解析职责。SSE 等异步请求会在 Servlet 线程释放时清理
 * ThreadLocal；后续异步回调只能使用 Controller 已显式传入 application 的 userId。</p>
 */
public class AuthenticationInterceptor implements AsyncHandlerInterceptor {

    private static final String LOCAL_USER_ID = "local-user";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHENTICATED_ATTRIBUTE =
            AuthenticationInterceptor.class.getName() + ".authenticated";

    private final ApplicationMode applicationMode;
    private final Optional<JwtTokenPort> jwtTokenPort;

    public AuthenticationInterceptor(ApplicationMode applicationMode, Optional<JwtTokenPort> jwtTokenPort) {
        this.applicationMode = applicationMode;
        this.jwtTokenPort = jwtTokenPort;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Tomcat 会复用工作线程，先清理异常路径可能遗留的主体，避免身份串扰。
        AuthenticatedPrincipalContext.clear();

        // 按启动模式选择认证方式
        AuthenticatedPrincipal principal = applicationMode == ApplicationMode.LOCAL
                ? localPrincipal()
                : onlinePrincipal(request);

        // 设置当前请求的认证信息
        AuthenticatedPrincipalContext.set(principal);
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);

        return true;
    }

    private AuthenticatedPrincipal localPrincipal() {
        // 本地模式是单用户可信环境，所有 HTTP 请求共享固定主体，避免业务层出现第二套 userId 逻辑。
        return new AuthenticatedPrincipal(LOCAL_USER_ID, LOCAL_USER_ID);
    }

    private AuthenticatedPrincipal onlinePrincipal(HttpServletRequest request) {
        JwtTokenPort tokenPort = jwtTokenPort.orElseThrow(
                () -> new IllegalStateException("线上模式未装配 JWT 认证服务"));
        String token = extractBearerToken(request);
        return tokenPort.parse(token);
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 异步请求离开当前 Servlet 线程时立刻清理，不能等待流结束才清理该工作线程。
        clearAuthenticatedPrincipal(request);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        clearAuthenticatedPrincipal(request);
    }

    // 从请求头中提取 Bearer Token
    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()) {
            throw new AuthenticationException();
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    // 清理当前线程中请求的认证信息
    private void clearAuthenticatedPrincipal(HttpServletRequest request) {
        if (request.getAttribute(AUTHENTICATED_ATTRIBUTE) != null) {
            AuthenticatedPrincipalContext.clear();
            request.removeAttribute(AUTHENTICATED_ATTRIBUTE);
        }
    }
}
