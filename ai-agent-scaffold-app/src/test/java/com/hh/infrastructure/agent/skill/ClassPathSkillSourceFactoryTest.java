package com.hh.infrastructure.agent.skill;

import com.google.adk.skills.SkillSource;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.impl.ClassPathSkillSourceFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ADK 官方 ClassPathSkillSource 能直接读取项目内 Skill，不依赖临时目录或外部文件系统。
 */
class ClassPathSkillSourceFactoryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "classpath:agent/skills",
            "classpath*:agent/skills/",
            "/agent/skills"
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
}
