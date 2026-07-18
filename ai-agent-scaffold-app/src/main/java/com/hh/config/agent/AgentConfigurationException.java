package com.hh.config.agent;

/**
 * 表示 catalog 或模块文件无法被安全地编译为 AgentDefinition。
 *
 * <p>异常消息只应包含资源路径、字段路径和稳定 ID，不得拼接模型密钥或 MCP header 值。</p>
 */
public class AgentConfigurationException extends RuntimeException {

    public AgentConfigurationException(String message) {
        super(message);
    }
}
