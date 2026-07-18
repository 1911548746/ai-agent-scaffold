package com.hh.config.agent;

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
    private Long timeout = 90L;
    @Min(0)
    private Integer maxRetries = 4;
    // 该字段不来自 YAML，Loader 在读取资源后回填它以标注校验失败的文件位置。
    private transient String sourcePath;
}
