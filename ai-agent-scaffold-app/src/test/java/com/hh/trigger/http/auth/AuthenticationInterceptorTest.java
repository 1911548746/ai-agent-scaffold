package com.hh.trigger.http.auth;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.types.enums.ApplicationMode;
import com.hh.types.exception.AuthenticationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

/** 验证 JWT 在 MVC Controller 之前建立身份，并在同步和异步边界清理。 */
class AuthenticationInterceptorTest {

    @AfterEach
    void clearPrincipal() {
        AuthenticatedPrincipalContext.clear();
    }

    @Test
    void interceptedRequestRejectsMissingBearerToken() {
        JwtTokenPort jwtTokenPort = mock(JwtTokenPort.class);
        AuthenticationInterceptor interceptor = onlineInterceptor(jwtTokenPort);

        assertThatThrownBy(() -> interceptor.preHandle(request(), response(), new Object()))
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(jwtTokenPort);
    }

    @Test
    void interceptedRequestEstablishesAndClearsPrincipalAfterCompletion() throws Exception {
        JwtTokenPort jwtTokenPort = mock(JwtTokenPort.class);
        when(jwtTokenPort.parse("signed-token"))
                .thenReturn(new AuthenticatedPrincipal("user-1", "alice"));
        AuthenticationInterceptor interceptor = onlineInterceptor(jwtTokenPort);
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer signed-token");

        assertThat(interceptor.preHandle(request, response(), new Object())).isTrue();
        assertThat(AuthenticatedPrincipalContext.requireCurrent().userId()).isEqualTo("user-1");
        verify(jwtTokenPort).parse("signed-token");

        interceptor.afterCompletion(request, response(), new Object(), null);
        assertThatThrownBy(AuthenticatedPrincipalContext::requireCurrent)
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void asyncRequestClearsPrincipalWhenServletThreadIsReleased() throws Exception {
        JwtTokenPort jwtTokenPort = mock(JwtTokenPort.class);
        when(jwtTokenPort.parse("signed-token"))
                .thenReturn(new AuthenticatedPrincipal("user-1", "alice"));
        AuthenticationInterceptor interceptor = onlineInterceptor(jwtTokenPort);
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer signed-token");

        interceptor.preHandle(request, response(), new Object());
        interceptor.afterConcurrentHandlingStarted(request, response(), new Object());

        // 异步回调不能依赖原 Servlet 线程的 ThreadLocal 身份。
        assertThatThrownBy(AuthenticatedPrincipalContext::requireCurrent)
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void localRequestEstablishesFixedLocalPrincipalWithoutBearerToken() throws Exception {
        JwtTokenPort jwtTokenPort = mock(JwtTokenPort.class);
        AuthenticationInterceptor interceptor = new AuthenticationInterceptor(
                ApplicationMode.LOCAL, Optional.of(jwtTokenPort));

        MockHttpServletRequest request = request();

        assertThat(interceptor.preHandle(request, response(), new Object())).isTrue();
        assertThat(AuthenticatedPrincipalContext.requireCurrent().userId()).isEqualTo("local-user");
        verifyNoInteractions(jwtTokenPort);
    }

    @Test
    void localRequestIgnoresClientAuthorizationAndStillUsesLocalPrincipal() throws Exception {
        JwtTokenPort jwtTokenPort = mock(JwtTokenPort.class);
        AuthenticationInterceptor interceptor = new AuthenticationInterceptor(
                ApplicationMode.LOCAL, Optional.of(jwtTokenPort));
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer forged-token");

        interceptor.preHandle(request, response(), new Object());

        assertThat(AuthenticatedPrincipalContext.requireCurrent().userId()).isEqualTo("local-user");
        verifyNoInteractions(jwtTokenPort);
    }

    private AuthenticationInterceptor onlineInterceptor(JwtTokenPort jwtTokenPort) {
        return new AuthenticationInterceptor(ApplicationMode.ONLINE, Optional.of(jwtTokenPort));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

}
