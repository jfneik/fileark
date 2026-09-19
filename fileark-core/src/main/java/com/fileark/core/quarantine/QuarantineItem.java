package com.fileark.core.quarantine;

import java.time.Instant;

/** 安全区条目。quarantinePath 下的文件已改名为 uuid，原名保存在 originalPath。 */
public record QuarantineItem(
        long id,
        String originalPath,
        String quarantinePath,
        long size,
        String hash,
        Instant quarantinedAt,
        Instant purgeAfter,
        boolean purged
) {
}
