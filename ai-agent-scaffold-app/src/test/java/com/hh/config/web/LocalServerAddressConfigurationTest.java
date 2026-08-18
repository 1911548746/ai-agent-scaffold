package com.hh.config.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;

import java.net.InetAddress;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证本地模式监听地址只提供默认值，不覆盖显式配置。 */
class LocalServerAddressConfigurationTest {

    @Test
    void defaultsToLoopbackWhenAddressIsNotConfigured() {
        ConfigurableServletWebServerFactory factory = mock(ConfigurableServletWebServerFactory.class);
        ServerProperties properties = new ServerProperties();

        new LocalServerAddressConfiguration()
                .localServerAddressCustomizer(properties)
                .customize(factory);

        verify(factory).setAddress(InetAddress.getLoopbackAddress());
    }

    @Test
    void preservesExplicitAddress() throws Exception {
        ConfigurableServletWebServerFactory factory = mock(ConfigurableServletWebServerFactory.class);
        ServerProperties properties = new ServerProperties();
        properties.setAddress(InetAddress.getByName("0.0.0.0"));

        new LocalServerAddressConfiguration()
                .localServerAddressCustomizer(properties)
                .customize(factory);

        verifyNoInteractions(factory);
    }
}
