package com.hh.config.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止示例模型配置在后续维护中重新写入明文凭据。
 */
class AgentCredentialConfigurationTest {

    private static final Pattern MODEL_API_KEY_PLACEHOLDER = Pattern.compile(
            "(?m)^\\s*api-key:\\s*\\$\\{MODEL_API_KEY}\\s*$"
    );

    @Test
    void modelConfigurationsUseTheEnvironmentVariablePlaceholder() throws IOException {
        Path modelDirectory = Path.of("src/main/resources/agent/models");
        List<Path> modelFiles;
        try (Stream<Path> paths = Files.list(modelDirectory)) {
            modelFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .toList();
        }

        assertThat(modelFiles)
                .as("至少保留一份模型示例配置，才能持续验证凭据规则")
                .isNotEmpty();
        for (Path modelFile : modelFiles) {
            // 断言只输出文件名和规则，不回显文件内容，避免失败日志意外携带凭据。
            boolean usesPlaceholder = MODEL_API_KEY_PLACEHOLDER.matcher(Files.readString(modelFile)).find();
            assertThat(usesPlaceholder)
                    .as("模型配置 %s 必须使用 MODEL_API_KEY 环境变量占位符", modelFile.getFileName())
                    .isTrue();
        }
    }

    @Test
    void committedEnvironmentTemplateContainsNoCredentialValue() throws IOException {
        Path templatePath = Files.exists(Path.of(".env.example"))
                ? Path.of(".env.example")
                : Path.of("../.env.example");
        List<String> templateLines = Files.readAllLines(templatePath);

        // 模板只声明变量名；空值避免它被误用为可运行的本地凭据文件。
        assertThat(templateLines).contains("MODEL_API_KEY=");
    }

}
