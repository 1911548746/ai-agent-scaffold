package com.hh.dev.adkweb;

import com.google.adk.web.AdkWebServer;
import com.hh.Application;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADK Web 本地开发启动器。
 *
 * <p>第一个 Spring Context 仅负责复用项目的 catalog 编译与 Agent 装配，不启动业务 Web；
 * 第二个 Context 只承载官方 ADK Web，并使用独立的内存 Session、Memory 和 Artifact。</p>
 */
public final class AdkWebDevelopmentLauncher {

    static final int DEFAULT_PORT = 8000;
    private static final Logger log = LoggerFactory.getLogger(AdkWebDevelopmentLauncher.class);
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String PORT_ENVIRONMENT_VARIABLE = "ADK_WEB_PORT";

    private AdkWebDevelopmentLauncher() {
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext scaffoldContext = null;
        ConfigurableApplicationContext adkWebContext = null;
        ScaffoldAgentLoader agentLoader = null;
        try {
            scaffoldContext = startScaffoldContext(args);
            AdkAgentRuntimeRegistry runtimeRegistry = scaffoldContext.getBean(AdkAgentRuntimeRegistry.class);
            agentLoader = ScaffoldAgentLoader.fromLeases(runtimeRegistry.acquireAll());

            int port = resolvePort(System.getenv(PORT_ENVIRONMENT_VARIABLE));
            adkWebContext = startAdkWebContext(agentLoader, port);
            registerShutdownHook(adkWebContext, scaffoldContext, agentLoader);
            log.info("ADK Web 开发调试入口已启动 address={}, port={}, agents={}",
                    LOOPBACK_ADDRESS, port, agentLoader.listAgents());
        } catch (RuntimeException | Error exception) {
            // Web Context 启动失败时必须释放已装配的模型、MCP 和数据库连接等父 Context 资源。
            closeContexts(adkWebContext, scaffoldContext, agentLoader);
            throw exception;
        }
    }

    /** 启动原项目组合根，但禁止业务端口和管理端口创建 Web Server。 */
    private static ConfigurableApplicationContext startScaffoldContext(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(args);
    }

    /** 启动与项目 application.yml 隔离的官方 ADK Web Context。 */
    private static ConfigurableApplicationContext startAdkWebContext(
            ScaffoldAgentLoader agentLoader,
            int port) {
        SpringApplication application = new SpringApplication(AdkWebServer.class);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(adkWebProperties(port));
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("agentLoader", agentLoader));

        // 指定独立配置名，防止第二个 Context 再次加载项目 datasource、catalog 和 Actuator 配置。
        return application.run(
                "--spring.config.name=adk-web",
                "--adk.agents.loader=custom");
    }

    /** ADK Web 首版固定只监听本机，并显式关闭与调试界面无关的数据库和管理端点。 */
    static Map<String, Object> adkWebProperties(int port) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", LOOPBACK_ADDRESS);
        properties.put("server.port", port);
        properties.put("management.server.port", -1);
        properties.put("management.endpoints.enabled-by-default", false);
        properties.put("management.endpoints.web.exposure.exclude", "*");
        properties.put("spring.main.banner-mode", "off");
        properties.put(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
        return Map.copyOf(properties);
    }

    /** 环境变量只允许合法 TCP 端口，避免静默回退到意外地址。 */
    static int resolvePort(String configuredPort) {
        if (configuredPort == null || configuredPort.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configuredPort);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        PORT_ENVIRONMENT_VARIABLE + " must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    PORT_ENVIRONMENT_VARIABLE + " must be a valid integer", exception);
        }
    }

    /** 统一关闭顺序：先停止调试请求，再释放项目装配出的 Agent 资源。 */
    private static void registerShutdownHook(
            ConfigurableApplicationContext adkWebContext,
            ConfigurableApplicationContext scaffoldContext,
            ScaffoldAgentLoader agentLoader) {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> closeContexts(adkWebContext, scaffoldContext, agentLoader),
                "adk-web-development-shutdown"));
    }

    private static void closeContexts(
            ConfigurableApplicationContext adkWebContext,
            ConfigurableApplicationContext scaffoldContext,
            ScaffoldAgentLoader agentLoader) {
        closeContext(adkWebContext, "ADK Web");
        if (agentLoader != null) {
            agentLoader.close();
        }
        closeContext(scaffoldContext, "scaffold");
    }

    private static void closeContext(ConfigurableApplicationContext context, String contextName) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (RuntimeException exception) {
            // 关闭失败只记录异常类型，避免第三方异常消息携带模型或工具配置。
            log.warn("关闭 {} Context 失败, errorType={}",
                    contextName, exception.getClass().getSimpleName());
        }
    }
}
