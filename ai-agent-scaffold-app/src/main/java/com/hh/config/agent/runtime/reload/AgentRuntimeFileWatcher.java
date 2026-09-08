package com.hh.config.agent.runtime.reload;

import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 监听外部 catalog 的完整目录树，并把文件变化转换为防抖后的自动 Reload。
 *
 * <p>该组件只监听本地 file: 配置。classpath 资源通常位于 JAR 内，无法可靠地接收文件系统事件，
 * 因此仍通过手动 Reload 或重启生效。</p>
 */
@Slf4j
@Component
public class AgentRuntimeFileWatcher implements SmartLifecycle {

    private final AgentCatalogLocationProperties locationProperties;
    private final AgentRuntimeReloadProperties reloadProperties;
    private final AgentRuntimeReloadApplicationService reloadService;
    private final AtomicLong changeVersion = new AtomicLong();
    private final Map<WatchKey, Path> directories = new HashMap<>();

    private volatile boolean running;
    private WatchService watchService;
    private ScheduledExecutorService scheduler;
    private Thread watchThread;
    private ScheduledFuture<?> debounceTask;
    private ScheduledFuture<?> retryTask;

    public AgentRuntimeFileWatcher(
            AgentCatalogLocationProperties locationProperties,
            AgentRuntimeReloadProperties reloadProperties,
            AgentRuntimeReloadApplicationService reloadService) {
        this.locationProperties = locationProperties;
        this.reloadProperties = reloadProperties;
        this.reloadService = reloadService;
    }

    @Override
    public synchronized void start() {
        if (running || !reloadProperties.getWatch().isEnabled()) {
            return;
        }

        Optional<Path> catalogPath = resolveFileCatalog();
        if (catalogPath.isEmpty()) {
            log.info("Agent Runtime 文件监听未启动，catalog 不是 file: 资源");
            return;
        }
        Path catalogDirectory = catalogPath.get().getParent();
        // 外部目录约定为 <config-root>/catalog/default-catalog.yml；监听配置根才能覆盖
        // models、tools、skills、agents、workflows 和 applications 等 catalog 同级资源。
        Path root = catalogDirectory != null
                && "catalog".equalsIgnoreCase(catalogDirectory.getFileName().toString())
                ? catalogDirectory.getParent()
                : catalogDirectory;
        if (root == null || !Files.isDirectory(root)) {
            log.warn("Agent Runtime 文件监听未启动，catalog 目录不存在, path={}", root);
            return;
        }

        try {
            watchService = root.getFileSystem().newWatchService();
            registerAll(root);
            scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("agent-runtime-reload"));
            running = true;
            watchThread = new Thread(this::watchLoop, "agent-runtime-config-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            log.info("Agent Runtime 文件监听已启动, root={}", root);
        } catch (IOException exception) {
            closeWatchService();
            log.warn("Agent Runtime 文件监听启动失败, errorType={}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running && scheduler == null && watchService == null) {
            return;
        }
        running = false;
        changeVersion.incrementAndGet();
        cancel(debounceTask);
        cancel(retryTask);
        closeWatchService();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (watchThread != null && watchThread != Thread.currentThread()) {
            watchThread.interrupt();
            watchThread = null;
        }
        directories.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                if (running) {
                    log.warn("Agent Runtime 文件监听异常, errorType={}", exception.getClass().getSimpleName());
                }
                return;
            }

            Path directory;
            synchronized (this) {
                directory = directories.get(key);
            }
            if (directory == null) {
                key.reset();
                continue;
            }

            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                changed = true;
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Path relative = (Path) event.context();
                Path child = directory.resolve(relative);
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    try {
                        registerAll(child);
                    } catch (IOException exception) {
                        log.warn("Agent Runtime 新目录监听注册失败, errorType={}",
                                exception.getClass().getSimpleName());
                    }
                }
            }
            if (!key.reset()) {
                synchronized (this) {
                    directories.remove(key);
                }
            }
            if (changed) {
                scheduleDebouncedReload();
            }
        }
    }

    private void scheduleDebouncedReload() {
        long version = changeVersion.incrementAndGet();
        synchronized (this) {
            cancel(debounceTask);
            cancel(retryTask);
            if (!running || scheduler == null) {
                return;
            }
            long delay = positiveMillis(reloadProperties.getWatch().getDebounce(), 10_000L);
            debounceTask = scheduler.schedule(() -> executeReload(version, 1), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 串行执行一次监听 Reload。
     *
     * <p>整个尝试与 {@link #stop()} 共用当前实例锁。停止操作会等待已经开始的编译尝试
     * 返回，然后再使版本号失效并取消后续任务，避免监听器停止后仍安排新的自动重试。</p>
     */
    private synchronized void executeReload(long version, int attempt) {
        if (!running || version != changeVersion.get()) {
            return;
        }

        AgentRuntimeReloadApplicationService.ReloadResult result;
        try {
            // 每次尝试都重新读取 catalog；Reload 服务内部会根据当前 digest 决定是否需要编译。
            result = attempt == 1
                    ? reloadService.reloadAutomatically()
                    : reloadService.retryAutomatically();
        } catch (RuntimeException exception) {
            log.warn("Agent Runtime 自动 Reload 调度失败, attempt={}, errorType={}",
                    attempt, exception.getClass().getSimpleName());
            return;
        }

        if (!"failure".equals(result.status())
                || version != changeVersion.get()
                || attempt >= Math.max(1, reloadProperties.getRetry().getMaxAttempts())) {
            return;
        }

        long delay = retryDelayMillis(attempt);
        if (running && version == changeVersion.get() && scheduler != null) {
            retryTask = scheduler.schedule(
                    () -> executeReload(version, attempt + 1), delay, TimeUnit.MILLISECONDS);
        }
    }

    private long retryDelayMillis(int failedAttempt) {
        long initial = positiveMillis(reloadProperties.getRetry().getInitialDelay(), 1000L);
        int shift = Math.min(Math.max(0, failedAttempt - 1), 30);
        long multiplier = 1L << shift;
        return initial > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : initial * multiplier;
    }

    private Optional<Path> resolveFileCatalog() {
        String location = locationProperties.getPath();
        if (location == null || !location.regionMatches(true, 0, "file:", 0, 5)) {
            return Optional.empty();
        }
        try {
            Path path = Paths.get(URI.create(location)).toAbsolutePath().normalize();
            return Optional.of(path);
        } catch (RuntimeException exception) {
            log.warn("Agent Runtime 文件监听路径无效, errorType={}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void registerAll(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                register(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private synchronized void register(Path directory) throws IOException {
        WatchKey key = directory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, directory);
    }

    private long positiveMillis(Duration duration, long fallback) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return fallback;
        }
        return Math.max(1L, duration.toMillis());
    }

    private ThreadFactory namedFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void closeWatchService() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException exception) {
                log.debug("关闭 Agent Runtime 文件监听失败, errorType={}",
                        exception.getClass().getSimpleName());
            }
            watchService = null;
        }
    }
}
