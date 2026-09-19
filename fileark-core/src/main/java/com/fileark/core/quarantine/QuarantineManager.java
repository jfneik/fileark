package com.fileark.core.quarantine;

import com.fileark.core.execute.FileOps;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 安全区：删除必经此地。入区即改名 uuid（避免冲突），
 * 恢复时若原父目录已不存在则重建；purgeAfter 前不清除。
 */
public final class QuarantineManager {

    private final Path quarantineDir;
    private final FileOps fileOps;
    private final Clock clock;
    private final long retentionDays;

    public QuarantineManager(Path quarantineDir, FileOps fileOps, Clock clock, long retentionDays) {
        this.quarantineDir = quarantineDir;
        this.fileOps = fileOps;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** 将 src 移入安全区，返回隔离后的路径。调用方须先写好撤销日志再调用。 */
    public Path admit(Path src) {
        try {
            fileOps.mkdirs(quarantineDir);
            Path target = quarantineDir.resolve(UUID.randomUUID() + extOf(src));
            fileOps.move(src, target);
            return target;
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "移入安全区失败: " + src, e.getMessage());
        }
    }

    /** 恢复到原路径；原父目录不存在则重建。 */
    public void restore(QuarantineItem item) {
        Path quarantined = Path.of(item.quarantinePath());
        Path original = Path.of(item.originalPath());
        if (!fileOps.exists(quarantined)) {
            throw new FileArkException(ErrorCode.QUARANTINE_CONFLICT,
                    "隔离文件已不存在: " + item.quarantinePath());
        }
        if (fileOps.exists(original)) {
            throw new FileArkException(ErrorCode.TARGET_CONFLICT,
                    "原路径已被占用，无法恢复: " + original);
        }
        try {
            fileOps.mkdirs(original.getParent());
            fileOps.move(quarantined, original);
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "恢复失败: " + original, e.getMessage());
        }
    }

    public void purgePhysically(QuarantineItem item) {
        try {
            java.nio.file.Files.deleteIfExists(Path.of(item.quarantinePath()));
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR,
                    "彻底删除失败: " + item.quarantinePath(), e.getMessage());
        }
    }

    public boolean expired(QuarantineItem item) {
        return Instant.now(clock).isAfter(item.purgeAfter());
    }

    public Instant purgeAfter(Instant now) {
        return now.plus(Duration.ofDays(retentionDays));
    }

    public Path dir() {
        return quarantineDir;
    }

    private static String extOf(Path src) {
        String name = src.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
