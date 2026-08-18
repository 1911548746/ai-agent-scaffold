package com.hh.config.application;

import com.hh.application.auth.usecase.AuthUseCase;
import com.hh.config.web.AuthenticationWebMvcConfiguration;
import com.hh.trigger.http.auth.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证本地模式不装配 JWT 用例，并能在无 JWT 配置时创建 MVC 认证链。 */
class ApplicationModeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void localModeSkipsOnlineAuthenticationConfiguration() {
        contextRunner
                .withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues("ai.application.mode=local")
                .run(context -> assertThat(context).doesNotHaveBean(AuthUseCase.class));
    }

    @Test
    void localModeCreatesMvcAuthenticationWithoutJwtBean() {
        contextRunner
                .withUserConfiguration(AuthenticationWebMvcConfiguration.class)
                .withPropertyValues("ai.application.mode=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuthenticationWebMvcConfiguration.class);
                    assertThat(context).doesNotHaveBean(com.hh.application.auth.port.JwtTokenPort.class);
                });
    }

    @Test
    void localModeDoesNotRegisterAuthenticationController() {
        contextRunner
                .withUserConfiguration(AuthControllerScan.class)
                .withPropertyValues("ai.application.mode=local")
                .run(context -> assertThat(context).doesNotHaveBean(AuthController.class));
    }

    @Test
    void onlineModeRegistersAuthenticationController() {
        contextRunner
                .withUserConfiguration(AuthControllerScan.class)
                .withBean(AuthUseCase.class, () -> mock(AuthUseCase.class))
                .withPropertyValues("ai.application.mode=online")
                .run(context -> assertThat(context).hasSingleBean(AuthController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AuthController.class)
    static class AuthControllerScan {
    }
}
