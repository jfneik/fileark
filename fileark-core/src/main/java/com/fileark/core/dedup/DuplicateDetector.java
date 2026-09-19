package com.fileark.core.dedup;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.hash.FileHasher;
import com.fileark.core.model.FileMeta;
import com.fileark.core.scan.ScanListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段查重管道：
 * 1) size 分桶 → 桶内抽样哈希分组 →「疑似重复组」（秒级，先给用户看）；
 * 2) confirm() 对单组做全量 SHA-256 确认——一个疑似组可能分裂为多个确认组，
 *    全量后仍唯一的文件被静默剔除（抽样误报）。
 */
public final class DuplicateDetector {

    /** 疑似组：成员经抽样哈希归并，sampleKey 已含文件大小。 */
    public record SuspectedGroup(String sampleKey, List<FileMeta> members) {
    }

    /** 确认组：全量 SHA-256 完全一致，contentHash 为组的内容凭证。 */
    public record ConfirmedGroup(String contentHash, List<FileMeta> members) {
    }

    public List<SuspectedGroup> findSuspected(List<FileMeta> files, ScanListener listener) {
        Map<Long, List<FileMeta>> bySize = new LinkedHashMap<>();
        for (FileMeta f : files) {
            if (f.size() > 0) {
                bySize.computeIfAbsent(f.size(), k -> new ArrayList<>()).add(f);
            }
        }
        Map<String, List<FileMeta>> bySample = new LinkedHashMap<>();
        int done = 0;
        for (List<FileMeta> bucket : bySize.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            for (FileMeta f : bucket) {
                bySample.computeIfAbsent(FileHasher.sampleKey(f.path(), f.size()),
                        k -> new ArrayList<>()).add(f);
                listener.onProgress(f.path(), ++done);
            }
        }
        return bySample.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new SuspectedGroup(e.getKey(), sorted(e.getValue())))
                .toList();
    }

    public List<ConfirmedGroup> confirm(SuspectedGroup group, ScanListener listener) {
        Map<String, List<FileMeta>> byFull = new LinkedHashMap<>();
        for (FileMeta f : sorted(group.members())) {
            byFull.computeIfAbsent(FileHasher.fullSha256(f.path()), k -> new ArrayList<>()).add(f);
            if (listener.cancelled()) {
                throw new FileArkException(ErrorCode.SCAN_CANCELLED, "确认已取消");
            }
        }
        return byFull.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new ConfirmedGroup(e.getKey(), sorted(e.getValue())))
                .toList();
    }

    private static List<FileMeta> sorted(List<FileMeta> members) {
        return members.stream().sorted(Comparator.comparing(f -> f.path().toString())).toList();
    }
}
