package com.hh.config.application;

import com.hh.types.enums.ApplicationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证启动模式的默认值和 YAML 枚举绑定行为。 */
class ApplicationModePropertiesTest {

    @Test
    void defaultsToOnlineMode() {
        ApplicationModeProperties properties = new ApplicationModeProperties();

        assertThat(properties.getMode()).isEqualTo(ApplicationMode.ONLINE);
        assertThat(properties.isLocal()).isFalse();
    }

    @Test
    void bindsLocalModeFromEnvironment() {
        ApplicationModeProperties properties = Binder.get(new MockEnvironment()
                        .withProperty("ai.application.mode", "local"))
                .bind("ai.application", Bindable.of(ApplicationModeProperties.class))
                .orElseGet(ApplicationModeProperties::new);

        assertThat(properties.getMode()).isEqualTo(ApplicationMode.LOCAL);
        assertThat(properties.isLocal()).isTrue();
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> Binder.get(new MockEnvironment()
                        .withProperty("ai.application.mode", "desktop"))
                .bind("ai.application", Bindable.of(ApplicationModeProperties.class)))
                .isInstanceOf(BindException.class);
    }
}
