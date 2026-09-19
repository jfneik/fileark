package com.fileark.core.scan;

import java.nio.file.Path;
import java.util.Set;

/** 扫描排除规则：命中目录名（任意层级）即整棵跳过。 */
public record Exclusions(Set<String> dirNames) {

    public static Exclusions defaults() {
        return new Exclusions(Set.of(
                ".git", ".svn", ".idea", ".vscode", "node_modules", "target",
                "build", "dist", "__pycache__", ".Trash", ".fileark"));
    }

    public boolean excluded(Path dir) {
        return dirNames.contains(dir.getFileName() == null ? "" : dir.getFileName().toString());
    }
}
