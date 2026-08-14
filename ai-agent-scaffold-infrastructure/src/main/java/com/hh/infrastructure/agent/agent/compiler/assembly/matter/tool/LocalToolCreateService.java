package com.hh.infrastructure.agent.agent.compiler.assembly.matter.tool;

import com.google.adk.tools.FunctionTool;
import com.hh.domain.agent.model.definition.LocalToolDefinition;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 将 Spring Bean 方法装配为 ADK 本地 FunctionTool。
 */
@Service
public class LocalToolCreateService {

    @Resource
    private ApplicationContext applicationContext;

    /** 按配置中的 Bean 名和方法名创建本地工具。 */
    public FunctionTool buildTool(LocalToolDefinition definition) {

        String beanName = definition.getBeanName();
        String methodName = definition.getMethodName();

        // 将beanName的首字母小写
        String actualBeanName = Character.toLowerCase(beanName.charAt(0)) + beanName.substring(1);

        Object bean;
        try {
            bean = applicationContext.getBean(actualBeanName);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Local tool bean not found: " + actualBeanName, exception);
        }

        try {
            return FunctionTool.create(bean, methodName);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Local tool method cannot be created: " + actualBeanName
                            + "." + methodName,
                    exception);
        }
    }
}
