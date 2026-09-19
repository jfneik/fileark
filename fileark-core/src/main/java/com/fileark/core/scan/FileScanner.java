package com.fileark.core.scan;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 递归遍历目录，收集普通文件路径；只读，不触碰文件内容。 */
public final class FileScanner {

    private final Exclusions exclusions;

    public FileScanner(Exclusions exclusions) {
        this.exclusions = exclusions;
    }

    public List<Path> collect(Path root, ScanListener listener) {
        if (!Files.isDirectory(root)) {
            throw new FileArkException(ErrorCode.INVALID_PATH, "扫描根目录不存在或不是目录: " + root);
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (listener.cancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return dir.equals(root) || !exclusions.excluded(dir)
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (listener.cancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile()) {
                        files.add(file);
                        listener.onProgress(file, files.size());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "遍历目录失败: " + root, e.getMessage());
        }
        if (listener.cancelled()) {
            throw new FileArkException(ErrorCode.SCAN_CANCELLED, "扫描已取消");
        }
        return files;
    }
}
