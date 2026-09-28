package com.fileark.core.watch;

/** 新文件稳定性判定：下载进行中的文件大小会持续变化，稳定后才允许自动整理。 */
public final class FileStabilityChecker {

    private final long settleMillis;

    public FileStabilityChecker(long settleMillis) {
        this.settleMillis = settleMillis;
    }

    public FileStabilityChecker() {
        this(200);
    }

    /** 两次采样大小一致且可读才视为稳定；消失中的临时文件返回 false。 */
    public boolean isStable(java.nio.file.Path p) {
        try {
            long first = java.nio.file.Files.size(p);
            Thread.sleep(settleMillis);
            if (!java.nio.file.Files.isRegularFile(p)) {
                return false;
            }
            long second = java.nio.file.Files.size(p);
            return first == second && second > 0 && java.nio.file.Files.isReadable(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
