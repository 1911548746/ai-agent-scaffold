package com.hh.config.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

/** 本地模式的监听地址默认值；显式 server.address 仍允许覆盖。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ai.application", name = "mode", havingValue = "local")
public class LocalServerAddressConfiguration {

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> localServerAddressCustomizer(
            ServerProperties serverProperties) {
        return factory -> {
            if (serverProperties.getAddress() == null) {
                factory.setAddress(InetAddress.getLoopbackAddress());
            }
        };
    }
}
