package com.fileark.core.execute;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/** 默认实现：真实文件系统。 */
public final class FsFileOps implements FileOps {

    @Override
    public boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    @Override
    public FileTime mtime(Path path) throws IOException {
        return Files.getLastModifiedTime(path);
    }

    @Override
    public void move(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 跨卷时退化为普通 move；仍不覆盖目标（无 REPLACE_EXISTING）
            Files.move(src, dst);
        }
    }

    @Override
    public List<Path> mkdirs(Path dir) throws IOException {
        List<Path> created = new ArrayList<>();
        if (dir == null || Files.exists(dir)) {
            return created;
        }
        // 从最远祖先到 dir 依次记录；撤销时逆序删除即「由深到浅、空才删」
        Path parent = dir.getParent();
        while (parent != null && !Files.exists(parent)) {
            created.add(0, parent);
            parent = parent.getParent();
        }
        Files.createDirectories(dir);
        created.add(dir);
        return created;
    }

    @Override
    public boolean deleteDirIfEmpty(Path dir) throws IOException {
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.newDirectoryStream(dir)) {
                if (stream.iterator().hasNext()) {
                    return false;
                }
            }
            Files.delete(dir);
            return true;
        }
        return false;
    }

    @Override
    public long usableSpace(Path root) {
        return root.toFile().getUsableSpace();
    }
}
