package com.hh.infrastructure.agent.compiler.assembly.matter.mcp.server;

import com.google.adk.tools.Annotations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service("myTestMcpServer")
public class MyTestMcpServer {

    @Annotations.Schema(name = "get_weather", description = "获取指定城市的天气信息")
    public Map<String, Object> getWeather(
            @Annotations.Schema(name = "city", description = "需要查询天气的城市名称") String city) {
        return Map.of("city", city, "weather", "雨天");
    }

}
