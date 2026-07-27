package com.hh.infrastructure.agent.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ADKJsonParseSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private ADKJsonParseSupport() {
    }

    public static String toJson(Object value) {
        try {
            return JsonBaseModel.getMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JsonBaseModel.getMapper().readValue(json, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化 Map 失败", e);
        }
    }

}
