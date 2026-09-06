package com.hh.infrastructure.agent.skill;

import com.google.adk.skills.SkillSource;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.impl.ClassPathSkillSourceFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ADK 官方 ClassPathSkillSource 能直接读取项目内 Skill，不依赖临时目录或外部文件系统。
 */
class ClassPathSkillSourceFactoryTest {

    @TempDir
    private Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {
            "classpath:agent/skill-resources",
            "classpath*:agent/skill-resources/",
            "/agent/skill-resources"
    })
    void loadsSkillAndReferencedResourceFromSupportedPathForms(String skillsDir) throws Exception {
        SkillSource source = new ClassPathSkillSourceFactory().createSkillSource(skillsDir);

        assertThat(source.listFrontmatters().blockingGet()).containsKey("release-note");
        assertThat(source.loadInstructions("release-note").blockingGet())
                .contains("请把用户提供的变更内容整理为发布说明");
        assertThat(new String(source.loadResource(
                        "release-note", "references/release-note-example.md").blockingGet().read(),
                StandardCharsets.UTF_8))
                .contains("Release Note Example", "新增能力", "修复问题");
    }

    @Test
    void loadsSkillFromLocalFileUri() throws Exception {
        Path skillDirectory = Files.createDirectories(temporaryDirectory.resolve("external-skill"));
        Path skillRoot = Files.createDirectories(skillDirectory.resolve("external-skill"));
        Files.writeString(skillRoot.resolve("SKILL.md"), """
                ---
                name: external-skill
                description: external skill
                license: internal-study
                compatibility: ADK Java 1.7.0
                allowed-tools: none
                metadata:
                  source: external-file
                ---

                external instructions
                """);

        SkillSource source = new ClassPathSkillSourceFactory()
                .createSkillSource(skillDirectory.toUri().toString());

        assertThat(Files.isDirectory(skillRoot)).isTrue();
        assertThat(Files.exists(skillRoot.resolve("SKILL.md"))).isTrue();
        assertThat(source.loadInstructions("external-skill").blockingGet())
                .contains("external instructions");
        assertThat(source.listFrontmatters().blockingGet()).containsKey("external-skill");
    }
}
