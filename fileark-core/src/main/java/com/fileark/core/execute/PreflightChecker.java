package com.fileark.core.execute;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.plan.Operation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行前预检：目标冲突、源变更、空间不足、路径非法。
 * 全部通过（或显式 SKIPPED）才允许执行——预检绝不写盘。
 */
public final class PreflightChecker {

    private final FileOps fileOps;

    public PreflightChecker(FileOps fileOps) {
        this.fileOps = fileOps;
    }

    /** 返回 operationId → 问题；未出现的操作视为通过。 */
    public Map<Long, PreflightIssue> check(List<Operation> ops) {
        Map<Long, PreflightIssue> issues = new LinkedHashMap<>();
        long bytesToMove = 0;
        for (Operation op : ops) {
            if (op.dst() != null && containsParentTraversal(op.dst())) {
                issues.put(op.id(), new PreflightIssue(ErrorCode.INVALID_PATH,
                        "目标路径包含非法的上级引用: " + op.dst()));
                continue;
            }
            if (op.dst() != null && fileOps.exists(op.dst())) {
                issues.put(op.id(), new PreflightIssue(ErrorCode.TARGET_CONFLICT,
                        "目标已存在（绝不覆盖）: " + op.dst()));
                continue;
            }
            try {
                if (sourceChanged(op)) {
                    issues.put(op.id(), new PreflightIssue(ErrorCode.SOURCE_CHANGED,
                            "源文件自扫描后已被修改: " + op.src()));
                    continue;
                }
            } catch (IOException e) {
                issues.put(op.id(), new PreflightIssue(ErrorCode.IO_ERROR,
                        "无法读取源文件状态: " + op.src() + " (" + e.getMessage() + ")"));
                continue;
            }
            if (op.type() == com.fileark.core.plan.OperationType.MOVE && op.dst() != null) {
                bytesToMove += op.expectedSize();
            }
        }
        if (bytesToMove > 0 && ops.stream().anyMatch(this::crossVolume)) {
            Path dstRoot = firstDstRoot(ops);
            if (dstRoot != null && fileOps.usableSpace(dstRoot) < bytesToMove) {
                issues.putIfAbsent(ops.getFirst().id(), new PreflightIssue(ErrorCode.INSUFFICIENT_SPACE,
                        "目标卷剩余空间不足: 需要 " + bytesToMove + " 字节"));
            }
        }
        return issues;
    }

    private boolean sourceChanged(Operation op) throws IOException {
        if (!fileOps.exists(op.src())) {
            return true;
        }
        if (op.expectedSize() <= 0 || op.expectedMtime() == null) {
            return false;
        }
        return fileOps.size(op.src()) != op.expectedSize()
                || !fileOps.mtime(op.src()).toInstant().equals(Instant.from(op.expectedMtime()));
    }

    private boolean crossVolume(Operation op) {
        return op.dst() != null && !op.src().getRoot().equals(op.dst().getRoot());
    }

    private Path firstDstRoot(List<Operation> ops) {
        return ops.stream().map(Operation::dst).filter(java.util.Objects::nonNull)
                .findFirst().map(java.nio.file.Path::getRoot).orElse(null);
    }

    static boolean containsParentTraversal(Path p) {
        for (Path part : p) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
