package com.fileark.core.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.StandardWatchEventKinds;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 目录实时监控（F6）：WatchService 递归注册 + 事件防抖 + 稳定性检查。
 * 事件在内部守护线程串行消费，回调 onNewFile 也在该线程执行——
 * 消费方无需再做并发控制，但回调必须快（本项目的回调即「生成并执行自动计划」）。
 */
public final class DirectoryWatcher implements AutoCloseable {

    private final Path dir;
    private final long debounceMillis;
    private final Consumer<Path> onNewFile;
    private final FileStabilityChecker stability;
    private final WatchService watchService;
    private final Set<Path> registered = new HashSet<>();
    /** path → 最近一次事件时间（防抖窗口的起点在首个事件处）。 */
    private final Map<Path, Long> lastEventAt = new HashMap<>();
    private Thread worker;
    private volatile boolean running;

    public DirectoryWatcher(Path dir, long debounceMillis,
                            Consumer<Path> onNewFile, FileStabilityChecker stability) throws IOException {
        this.dir = dir;
        this.debounceMillis = debounceMillis;
        this.onNewFile = onNewFile;
        this.stability = stability;
        this.watchService = dir.getFileSystem().newWatchService();
    }

    public Path dir() {
        return dir;
    }

    /** 同步完成递归注册后再启动消费线程——保证 start() 返回后发生的事件一定被捕获。
     *  启动时不补扫既有文件：它们不是「新文件」，不应被自动整理。 */
    public void start() throws IOException {
        registerRecursive(dir, false);
        running = true;
        worker = new Thread(this::loop, "fileark-watch-" + dir.getFileName());
        worker.setDaemon(true);
        worker.start();
    }

    private void registerRecursive(Path root, boolean sweepExisting) throws IOException {
        if (registered.contains(root)) {
            return;
        }
        registered.add(root);
        root.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        try (var stream = Files.list(root)) {
            for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                Path child = it.next();
                if (Files.isDirectory(child)) {
                    registerRecursive(child, sweepExisting);
                } else if (sweepExisting && Files.isRegularFile(child)) {
                    // 监控期间新建的子树：注册前已落入的文件按「新文件」处理（时间戳归零即视为防抖到期）
                    lastEventAt.put(child, 0L);
                }
            }
        }
    }

    private void loop() {
        try {
            while (running) {
                WatchKey key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key != null) {
                    drain(key);
                }
                fireAgedEvents();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly();
        }
    }

    @SuppressWarnings("unchecked")
    private void drain(WatchKey key) {
        Path watched = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            Path child = watched.resolve(((WatchEvent<Path>) event).context());
            try {
                if (Files.isDirectory(child) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerRecursive(child, true);
                } else if (Files.isRegularFile(child)) {
                    lastEventAt.put(child, System.currentTimeMillis());
                }
            } catch (IOException ignored) {
                // 子目录注册失败：跳过该子树
            }
        }
        if (!key.reset()) {
            registered.remove(watched);
        }
    }

    /** 防抖到期且文件稳定 → 回调。 */
    private void fireAgedEvents() {
        long now = System.currentTimeMillis();
        lastEventAt.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < debounceMillis) {
                return false;
            }
            Path file = entry.getKey();
            if (Files.isRegularFile(file) && stability.isStable(file)) {
                try {
                    onNewFile.accept(file);
                } catch (RuntimeException e) {
                    // 单个文件的自动整理失败不终止监控
                }
            }
            return true;
        });
    }

    private void closeQuietly() {
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        closeQuietly();
    }
}
