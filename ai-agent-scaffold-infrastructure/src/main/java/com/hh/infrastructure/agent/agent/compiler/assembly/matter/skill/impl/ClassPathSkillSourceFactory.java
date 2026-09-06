package com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.impl;

import com.google.adk.skills.ClassPathSkillSource;
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.SkillSourceFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 根据资源前缀创建 ADK 官方 Skill 资源源。
 *
 * <p>ADK 1.6.0 起可直接读取 classpath 中的 SKILL.md 和附属资源，不再需要将 Spring Boot
 * JAR 内资源复制到临时目录；外部 catalog 解析出的 {@code file:} 目录则直接使用
 * {@link LocalSkillSource}。保留原类名和工厂入口，避免改变现有装配链路。</p>
 */
@Component
public class ClassPathSkillSourceFactory implements SkillSourceFactory {

    @Override
    public SkillSource createSkillSource(String skillsDir) {
        if (skillsDir != null && skillsDir.trim().startsWith("file:")) {
            return new LocalSkillSource(toLocalPath(skillsDir));
        }
        String basePath = extractBasePath(skillsDir);
        if (basePath.isBlank()) {
            throw new IllegalArgumentException("skillsDir must point to a classpath directory");
        }
        return new ClassPathSkillSource(basePath);
    }

    private Path toLocalPath(String skillsDir) {
        try {
            return Paths.get(URI.create(skillsDir.trim())).toAbsolutePath().normalize();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("skillsDir must be a valid file URI", exception);
        }
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
