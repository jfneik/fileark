package com.fileark.core.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 文件索引条目。id 在入库前为 0，由仓储回填。
 */
public record FileMeta(
        long id,
        Path path,
        long size,
        Instant mtime,
        String mimeType,
        Category category
) {
}
