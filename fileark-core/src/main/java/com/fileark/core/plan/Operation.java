package com.fileark.core.plan;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 单条操作。expectedSize/expectedMtime 来自扫描时的元数据，
 * 预检阶段用于发现「扫描后文件已被修改」。
 */
public record Operation(
        long id,
        OperationType type,
        Path src,
        Path dst,
        long expectedSize,
        Instant expectedMtime,
        OperationStatus status,
        String error
) {
    public static Operation pending(OperationType type, Path src, Path dst,
                                    long expectedSize, Instant expectedMtime) {
        return new Operation(0, type, src, dst, expectedSize, expectedMtime,
                OperationStatus.PENDING, null);
    }

    public Operation withId(long id) {
        return new Operation(id, type, src, dst, expectedSize, expectedMtime, status, error);
    }
}
