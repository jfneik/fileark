package com.fileark.core.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryWatcherTest {

    @TempDir
    Path dir;

    @Test
    void newStableFileTriggersCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Path[] received = new Path[1];
        DirectoryWatcher watcher = new DirectoryWatcher(dir, 150,
                p -> {
                    received[0] = p;
                    latch.countDown();
                }, new FileStabilityChecker(50));
        watcher.start();
        try {
            Files.write(dir.resolve("new-file.txt"), "hello".getBytes());
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("5 秒内应收到新文件回调").isTrue();
            assertThat(received[0]).isEqualTo(dir.resolve("new-file.txt"));
        } finally {
            watcher.close();
        }
    }

    @Test
    void unstableFileDoesNotTrigger() throws Exception {
        // 持续写入 2.5s 且每次写入严格变大（模拟下载）：稳定性检查（400ms）窗口内
        // 大小必然变化 → 永不稳定 → 不回调。注意：稳定性判定基于大小，
        // 同大小的重写不算「不稳定」——这是刻意的设计边界（真实下载文件持续增大）。
        CountDownLatch latch = new CountDownLatch(1);
        DirectoryWatcher watcher = new DirectoryWatcher(dir, 100,
                p -> latch.countDown(), new FileStabilityChecker(400));
        watcher.start();
        try {
            Path target = dir.resolve("growing.bin");
            long deadline = System.currentTimeMillis() + 2500;
            int i = 0;
            while (System.currentTimeMillis() < deadline) {
                Files.write(target, new byte[200 + i * 50]);
                i++;
                Thread.sleep(150);
                assertThat(latch.getCount()).as("持续变化期间不应触发回调").isEqualTo(1);
            }
        } finally {
            watcher.close();
        }
    }

    @Test
    void nestedDirectoryCreationIsMonitored() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Path[] received = new Path[1];
        DirectoryWatcher watcher = new DirectoryWatcher(dir, 150,
                p -> {
                    received[0] = p;
                    latch.countDown();
                }, new FileStabilityChecker(50));
        watcher.start();
        try {
            Path sub = Files.createDirectories(dir.resolve("sub"));
            Files.write(sub.resolve("inner.txt"), "x".getBytes());
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received[0]).isEqualTo(sub.resolve("inner.txt"));
        } finally {
            watcher.close();
        }
    }
}
