package com.hh.domain.agent.model.definition;

import lombok.Data;

/**
 * 供一个 AgentDefinition 内全部节点共享的模型连接定义。
 *
 * <p>密钥只在内存装配阶段使用，调用方不得记录该对象的完整内容。</p>
 */
@Data
public class ChatModelDefinition {

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Long timeout = 90L;
    private Integer maxRetries = 4;
}
