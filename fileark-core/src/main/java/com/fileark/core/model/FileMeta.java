package com.fileark.core.model;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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

    /** 从磁盘读取单个文件的元数据（监控场景：新文件落地即建条目）。 */
    public static FileMeta of(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            FileClassify.Detected detected = FileClassify.detect(file);
            return new FileMeta(0, file, attrs.size(), attrs.lastModifiedTime().toInstant(),
                    detected.mimeType(), detected.category());
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "读取文件元数据失败: " + file, e.getMessage());
        }
    }
}
