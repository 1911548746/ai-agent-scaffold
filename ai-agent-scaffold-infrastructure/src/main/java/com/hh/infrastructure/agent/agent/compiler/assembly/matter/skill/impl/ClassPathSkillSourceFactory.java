package com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.impl;

import com.google.adk.skills.ClassPathSkillSource;
import com.google.adk.skills.SkillSource;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.SkillSourceFactory;
import org.springframework.stereotype.Component;

/**
 * 使用 ADK 官方 ClassPathSkillSource 创建 Skill 资源源。
 *
 * <p>ADK 1.6.0 起可直接读取 classpath 中的 SKILL.md 和附属资源，不再需要将 Spring Boot
 * JAR 内资源复制到临时目录。保留工厂接口，便于未来增加文件系统或远程 SkillSource。</p>
 */
@Component
public class ClassPathSkillSourceFactory implements SkillSourceFactory {

    @Override
    public SkillSource createSkillSource(String skillsDir) {
        String basePath = extractBasePath(skillsDir);
        if (basePath.isBlank()) {
            throw new IllegalArgumentException("skillsDir must point to a classpath directory");
        }
        return new ClassPathSkillSource(basePath);
    }

    private String extractBasePath(String skillsDir) {
        if (skillsDir == null) {
            return "";
        }
        String basePath = skillsDir.trim();
        if (basePath.startsWith("classpath:")) {
            basePath = basePath.substring("classpath:".length());
        }
        if (basePath.startsWith("classpath*:")) {
            basePath = basePath.substring("classpath*:".length());
        }
        // ClassPathSkillSource 使用相对 classpath 根路径，前导斜杠会导致资源匹配失败。
        return basePath.replace('\\', '/').replaceFirst("^/+", "").replaceFirst("/+$", "");
    }
}
