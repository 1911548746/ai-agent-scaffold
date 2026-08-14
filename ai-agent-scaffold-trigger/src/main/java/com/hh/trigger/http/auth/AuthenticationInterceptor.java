package com.hh.trigger.http.auth;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.types.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * HTTP 入站认证拦截器：在 Controller 执行前验证 Bearer JWT 并建立当前用户主体。
 *
 * <p>认证属于 HTTP 协议边界，使用 MVC 拦截器可以在 Controller 之前直接拒绝请求，
 * 而不是让业务切面承担 Header 解析职责。SSE 等异步请求会在 Servlet 线程释放时清理
 * ThreadLocal；后续异步回调只能使用 Controller 已显式传入 application 的 userId。</p>
 */
public class AuthenticationInterceptor implements AsyncHandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHENTICATED_ATTRIBUTE =
            AuthenticationInterceptor.class.getName() + ".authenticated";

    private final JwtTokenPort jwtTokenPort;

    public AuthenticationInterceptor(JwtTokenPort jwtTokenPort) {
        this.jwtTokenPort = jwtTokenPort;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Tomcat 会复用工作线程，先清理异常路径可能遗留的主体，避免身份串扰。
        AuthenticatedPrincipalContext.clear();

        String token = extractBearerToken(request);
        AuthenticatedPrincipal principal = jwtTokenPort.parse(token);
        AuthenticatedPrincipalContext.set(principal);
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);

        return true;
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

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()) {
            throw new AuthenticationException();
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    private void clearAuthenticatedPrincipal(HttpServletRequest request) {
        if (request.getAttribute(AUTHENTICATED_ATTRIBUTE) != null) {
            AuthenticatedPrincipalContext.clear();
            request.removeAttribute(AUTHENTICATED_ATTRIBUTE);
        }
    }
}
