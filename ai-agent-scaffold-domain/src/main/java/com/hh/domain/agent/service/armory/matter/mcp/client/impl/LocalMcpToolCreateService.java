package com.hh.domain.agent.service.armory.matter.mcp.client.impl;
import com.google.adk.tools.FunctionTool;
import com.hh.domain.agent.model.legacyconfig.McpTool;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class LocalMcpToolCreateService implements McpToolCreateService<FunctionTool> {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public FunctionTool buildMcpToolset(McpTool mcpToolConfig) {

        McpTool.LocalServerParameters toolConfigLocal = mcpToolConfig.getLocal();
        String beanName = toolConfigLocal.getBeanName();
        String methodName = toolConfigLocal.getMethodName();

        // 将beanName的首字母小写
        String actualBeanName = Character.toLowerCase(beanName.charAt(0)) + beanName.substring(1);

        Object bean = applicationContext.getBean(actualBeanName);
        if (bean == null) {
            throw new RuntimeException("Bean not found: " + beanName);
        }

        FunctionTool tool = FunctionTool.create(bean, methodName);
        return tool;

    }
}
