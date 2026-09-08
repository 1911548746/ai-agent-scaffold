package com.hh.config.agent;

import com.hh.config.agent.runtime.reload.AgentRuntimeFileWatcher;
import com.hh.config.agent.runtime.reload.AgentRuntimeReloadApplicationService;
import com.hh.config.agent.runtime.reload.AgentRuntimeReloadProperties;
import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 file: catalog 的监听启动、嵌套目录发现和停止行为。 */
class AgentRuntimeFileWatcherTest {

    @Test
    void watchesNestedCatalogFilesAndStopsCleanly() throws Exception {
        Path root = Files.createTempDirectory("agent-catalog-watch");
        Path catalog = root.resolve("catalog.yml");
        Path nested = root.resolve("agents");
        Files.createDirectories(nested);
        Files.writeString(catalog, "catalog: v1");
        Files.writeString(nested.resolve("agent.yml"), "agent: v1");

        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        location.setPath(catalog.toUri().toString());
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();
        properties.getWatch().setEnabled(true);
        properties.getWatch().setDebounce(java.time.Duration.ofMillis(20));
        properties.getRetry().setMaxAttempts(1);
        AgentRuntimeReloadApplicationService reloadService = mock(AgentRuntimeReloadApplicationService.class);
        when(reloadService.reloadAutomatically()).thenReturn(
                new AgentRuntimeReloadApplicationService.ReloadResult(
                        "unchanged", 1, "digest", 1, 0, null));

        AgentRuntimeFileWatcher watcher = new AgentRuntimeFileWatcher(location, properties, reloadService);
        watcher.start();
        try {
            Thread.sleep(100);
            Files.writeString(nested.resolve("agent.yml"), "agent: v2");
            verify(reloadService, timeout(1500)).reloadAutomatically();
            assertThat(watcher.isRunning()).isTrue();
        } finally {
            watcher.stop();
        }

        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void doesNotStartForClasspathCatalog() {
        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();
        properties.getWatch().setEnabled(true);

        AgentRuntimeFileWatcher watcher = new AgentRuntimeFileWatcher(
                location, properties, mock(AgentRuntimeReloadApplicationService.class));
        watcher.start();

        assertThat(watcher.isRunning()).isFalse();
        watcher.stop();
    }

    @Test
    void retriesFailedFileReloadUpToConfiguredAttemptCount() throws Exception {
        Path root = Files.createTempDirectory("agent-catalog-retry");
        Path catalog = root.resolve("catalog.yml");
        Path nested = root.resolve("agents");
        Files.createDirectories(nested);
        Files.writeString(catalog, "catalog: v1");
        Path changed = nested.resolve("agent-retry.yml");
        Files.writeString(changed, "agent: v1");

        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        location.setPath(catalog.toUri().toString());
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();
        properties.getWatch().setEnabled(true);
        properties.getWatch().setDebounce(java.time.Duration.ofMillis(300));
        properties.getRetry().setInitialDelay(java.time.Duration.ofMillis(10));
        properties.getRetry().setMaxAttempts(3);
        AgentRuntimeReloadApplicationService.ReloadResult failure =
                new AgentRuntimeReloadApplicationService.ReloadResult(
                        "failure", 1, "old-digest", 1, 1, "failure");
        AgentRuntimeReloadApplicationService reloadService =
                mock(AgentRuntimeReloadApplicationService.class);
        when(reloadService.reloadAutomatically()).thenReturn(failure);
        when(reloadService.retryAutomatically()).thenReturn(failure);

        AgentRuntimeFileWatcher watcher = new AgentRuntimeFileWatcher(location, properties, reloadService);
        watcher.start();
        try {
            // Windows 的 WatchService 线程启动和目录注册存在可见性延迟，先留出稳定窗口再修改文件。
            Thread.sleep(500);
            Files.writeString(changed, "agent: v2");
            verify(reloadService, timeout(1500).times(1)).reloadAutomatically();
            verify(reloadService, timeout(1500).times(2)).retryAutomatically();
        } finally {
            watcher.stop();
        }
    }
}
