package com.hh.config.agent.catalog.binding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 一个 models/*.yml 文件的绑定模型。
 *
 * <p>模型 ID 供 Agent 的 model-ref 使用；sourcePath 仅用于形成可定位且不泄露凭据的错误消息。</p>
 */
@Data
@Validated
public class ModelModuleConfig {

    @NotNull
    @Min(1)
    private Integer schemaVersion;
    @NotBlank
    private String id;
    @NotBlank
    private String baseUrl;
    @NotBlank
    private String apiKey;
    @NotBlank
    private String modelName;
    @Min(1)
    private Long timeout = 30L;
    @Min(0)
    private Integer maxRetries = 4;
    /** 流式调用等待首个文本或控制响应的最长秒数，不承担首响应后的逐段读取超时。 */
    @NotNull
    @Min(1)
    private Long streamingFirstResponseTimeout = 30L;
    /** 仅在尚未收到任何流式响应时生效，避免重放已经产生可见输出的请求。 */
    @NotNull
    @Min(0)
    @Max(5)
    private Integer streamingMaxRetries = 2;
    // 该字段不来自 YAML，Loader 在读取资源后回填它以标注校验失败的文件位置。
    private transient String sourcePath;
}
