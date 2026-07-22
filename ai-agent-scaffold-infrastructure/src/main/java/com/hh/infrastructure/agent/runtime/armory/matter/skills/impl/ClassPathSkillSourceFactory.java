package com.hh.infrastructure.agent.runtime.armory.matter.skills.impl;

import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.hh.infrastructure.agent.runtime.armory.matter.skills.SkillSourceFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ClassPathSkillSourceFactory implements SkillSourceFactory {

    private final ResourceLoader resourceLoader;
    private Path tempSkillsDir;

    public ClassPathSkillSourceFactory(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() throws IOException {
        tempSkillsDir = Files.createTempDirectory("ai-agent-skills-");
    }

    @PreDestroy
    public void cleanup() throws IOException {
        FileSystemUtils.deleteRecursively(tempSkillsDir);
    }

    @Override
    public SkillSource createSkillSource(String skillsDir) {
        try {

            String basePath = extractBasePath(skillsDir);
            String classpathLocation = "classpath:" + basePath;

            Resource resource = resourceLoader.getResource(classpathLocation);

            if (resource.isFile()) {
                // 开发环境：直接使用文件系统路径
                return new LocalSkillSource(resource.getFile().toPath());
            } else {
                // JAR包环境：解压到临时目录
                Path targetDir = tempSkillsDir.resolve(hash(skillsDir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                    // 递归复制资源到临时目录...
                    copyResourceToDirectory(basePath, targetDir);
                }
                return new LocalSkillSource(targetDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skills from: " + skillsDir, e);
        }
    }

    private String extractBasePath(String skillsDir) {
        if (skillsDir.startsWith("classpath:")) {
            return skillsDir.substring("classpath:".length());
        }
        if (skillsDir.startsWith("classpath*:")) {
            return skillsDir.substring("classpath*:".length());
        }
        return skillsDir;
    }

    // 资源复制逻辑
    private void copyResourceToDirectory(String basePath, Path targetDir) throws IOException {
        String pattern = "classpath*:" + basePath + "/**";
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
        Resource[] resources = resolver.getResources(pattern);

        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }

            String relativePath = resolveRelativePath(resource, basePath);
            if (relativePath == null || relativePath.isEmpty()) {
                continue;
            }

            Path targetFile = targetDir.resolve(relativePath);
            Path parentDir = targetFile.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            if (resource.contentLength() > 0) {
                try (InputStream in = resource.getInputStream();
                     OutputStream out = Files.newOutputStream(targetFile)) {
                    StreamUtils.copy(in, out);
                }
            }
        }
    }

    private String resolveRelativePath(Resource resource, String basePath) throws IOException {
        String uriPath = resource.getURI().toString();
        String normalizedBase = basePath.endsWith("/") ? basePath : basePath + "/";
        int baseIndex = uriPath.lastIndexOf(normalizedBase);
        if (baseIndex == -1) {
            normalizedBase = basePath.startsWith("/") ? basePath.substring(1) : basePath;
            normalizedBase = "/" + normalizedBase + "/";
            baseIndex = uriPath.lastIndexOf(normalizedBase);
        }
        if (baseIndex == -1) {
            return null;
        }
        return uriPath.substring(baseIndex + normalizedBase.length());
    }

    private String hash(String input) {
        return String.valueOf(input.hashCode());
    }
}
