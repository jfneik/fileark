package com.fileark.core.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.ScanStore;
import com.fileark.core.scan.FileScanner;
import com.fileark.core.scan.ScanListener;
import com.fileark.core.scan.ScanStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 扫描编排器：遍历 → 元数据入库 → 抽样出疑似组 → 虚拟线程并行全量确认 → 落库。
 * 全量哈希是 IO 密集任务，虚拟线程 + 信号量限流防止文件句柄耗尽。
 */
public final class ScanOrchestrator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScanStore scanStore;
    private final FileRepository fileRepository;
    private final DuplicateStore duplicateStore;
    private final DuplicateDetector detector;
    private final ExecutorService hashPool;
    private final int hashConcurrency;

    public ScanOrchestrator(ScanStore scanStore, FileRepository fileRepository,
                            DuplicateStore duplicateStore, DuplicateDetector detector,
                            ExecutorService hashPool, int hashConcurrency) {
        this.scanStore = scanStore;
        this.fileRepository = fileRepository;
        this.duplicateStore = duplicateStore;
        this.detector = detector;
        this.hashPool = hashPool;
        this.hashConcurrency = hashConcurrency;
    }

    public long createScan(Path root) {
        return scanStore.create(root.toString());
    }

    public void runScan(long scanId, Path root, ScanEventListener listener) {
        try {
            listener.onPhase("SCANNING");
            List<Path> paths = new FileScanner(com.fileark.core.scan.Exclusions.defaults())
                    .collect(root, new ScanListener() {
                        @Override
                        public void onProgress(Path current, int count) {
                            listener.onProgress(count, current.toString());
                        }

                        @Override
                        public boolean cancelled() {
                            return listener.cancelled();
                        }
                    });

            List<FileMeta> metas = new ArrayList<>(paths.size());
            for (Path p : paths) {
                metas.add(FileMeta.of(p));
            }
            // 增量扫描（F10）：path+size+mtime 未变的文件直接复用历史全量哈希
            Map<String, String> knownHashes = new LinkedHashMap<>();
            for (FileMeta m : metas) {
                fileRepository.findReusableContentHash(m.path().toString(), m.size(), m.mtime())
                        .ifPresent(h -> knownHashes.put(m.path().toString(), h));
            }
            metas = fileRepository.insertAll(scanId, metas);

            listener.onPhase("SUSPECTING");
            // 编排层事件接口与扫描进度接口是两个体系，此处做一次桥接
            List<DuplicateDetector.SuspectedGroup> suspected = detector.findSuspected(metas,
                    new ScanListener() {
                        @Override
                        public void onProgress(Path current, int count) {
                            listener.onProgress(count, current.toString());
                        }

                        @Override
                        public boolean cancelled() {
                            return listener.cancelled();
                        }
                    });
            listener.onSuspectedGroups(suspected.size());
            List<Long> suspectedIds = new ArrayList<>();
            for (DuplicateDetector.SuspectedGroup g : suspected) {
                suspectedIds.add(duplicateStore.createGroup(scanId, g.sampleKey(),
                        DuplicateStore.GroupStatus.SUSPECTED, ids(g.members())));
            }

            listener.onPhase("CONFIRMING");
            java.util.concurrent.atomic.AtomicLong reused = new java.util.concurrent.atomic.AtomicLong();
            long reclaimable = 0;
            int confirmedGroups = 0;
            for (int i = 0; i < suspected.size(); i++) {
                if (listener.cancelled()) {
                    suspectedIds.forEach(id -> duplicateStore.updateStatus(id,
                            DuplicateStore.GroupStatus.SUSPECTED));
                    scanStore.updateStatus(scanId, ScanStatus.CANCELLED, stats(metas.size(), 0, 0, 0));
                    return;
                }
                List<DuplicateDetector.ConfirmedGroup> confirmed =
                        confirmInParallel(suspected.get(i), knownHashes, reused);
                for (DuplicateDetector.ConfirmedGroup cg : confirmed) {
                    duplicateStore.createGroup(scanId, cg.contentHash(),
                            DuplicateStore.GroupStatus.CONFIRMED, ids(cg.members()));
                    cg.members().forEach(m -> fileRepository.updateContentHash(m.id(), cg.contentHash()));
                    confirmedGroups++;
                    reclaimable += (cg.members().size() - 1L) * cg.members().getFirst().size();
                }
                duplicateStore.deleteGroup(suspectedIds.get(i));
                listener.onGroupConfirmed(i + 1, suspected.size(), confirmed.size());
            }

            scanStore.updateStatus(scanId, ScanStatus.DONE,
                    stats(metas.size(), confirmedGroups, reclaimable, reused.get()));
            listener.onPhase("DONE");
        } catch (FileArkException e) {
            if (e.code() == com.fileark.core.exception.ErrorCode.SCAN_CANCELLED) {
                scanStore.updateStatus(scanId, ScanStatus.CANCELLED, null);
                listener.onPhase("CANCELLED");
            } else {
                scanStore.updateStatus(scanId, ScanStatus.FAILED, errorStats(e));
                listener.onPhase("FAILED");
                throw e;
            }
        } catch (RuntimeException e) {
            scanStore.updateStatus(scanId, ScanStatus.FAILED, errorStats(e));
            listener.onPhase("FAILED");
            throw e;
        }
    }

    /** 组内成员并行全量哈希；信号量限制并发文件句柄数；已知哈希直接复用。 */
    private List<DuplicateDetector.ConfirmedGroup> confirmInParallel(
            DuplicateDetector.SuspectedGroup group, Map<String, String> knownHashes,
            java.util.concurrent.atomic.AtomicLong reused) {
        Semaphore permits = new Semaphore(hashConcurrency);
        List<Future<String>> futures = new ArrayList<>();
        for (FileMeta m : group.members()) {
            futures.add(hashPool.submit(() -> {
                String known = knownHashes.get(m.path().toString());
                if (known != null) {
                    reused.incrementAndGet();
                    return known;
                }
                permits.acquire();
                try {
                    return com.fileark.core.hash.FileHasher.fullSha256(m.path());
                } finally {
                    permits.release();
                }
            }));
        }
        Map<String, List<FileMeta>> byDigest = new LinkedHashMap<>();
        for (int i = 0; i < group.members().size(); i++) {
            try {
                byDigest.computeIfAbsent(futures.get(i).get(), k -> new ArrayList<>())
                        .add(group.members().get(i));
            } catch (Exception e) {
                throw new FileArkException(com.fileark.core.exception.ErrorCode.IO_ERROR,
                        "哈希任务失败: " + group.members().get(i).path(), e.getMessage());
            }
        }
        return byDigest.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new DuplicateDetector.ConfirmedGroup(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<Long> ids(List<FileMeta> members) {
        return members.stream().map(FileMeta::id).toList();
    }

    private static String stats(long files, long groups, long reclaimable, long reusedHashes) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "files", files, "confirmedGroups", groups,
                    "reclaimableBytes", reclaimable, "reusedHashes", reusedHashes));
        } catch (IOException e) {
            return null;
        }
    }

    private static String errorStats(Exception e) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("error", String.valueOf(e.getMessage()));
            return JSON.writeValueAsString(m);
        } catch (IOException ignored) {
            return null;
        }
    }
}
