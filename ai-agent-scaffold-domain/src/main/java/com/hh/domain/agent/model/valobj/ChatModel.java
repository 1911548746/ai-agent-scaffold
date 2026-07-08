package com.hh.domain.agent.model.valobj;

import lombok.Data;

@Data
public class ChatModel {
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Long timeout = 90L;
    private Integer maxRetries = 4;
}
