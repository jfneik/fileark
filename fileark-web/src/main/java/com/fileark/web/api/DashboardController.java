package com.fileark.web.api;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.QuarantineStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.scan.ScanStatus;
import com.fileark.core.quarantine.QuarantineItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 仪表盘（F8）：最近一次扫描的查重收益 + 计划/安全区全貌 + 类型分布。 */
@RestController
public class DashboardController {

    private final ScanStore scanStore;
    private final FileRepository fileRepository;
    private final DuplicateStore duplicateStore;
    private final PlanStore planStore;
    private final QuarantineStore quarantineStore;
    private final Clock clock;

    public DashboardController(ScanStore scanStore, FileRepository fileRepository,
                               DuplicateStore duplicateStore, PlanStore planStore,
                               QuarantineStore quarantineStore, Clock clock) {
        this.scanStore = scanStore;
        this.fileRepository = fileRepository;
        this.duplicateStore = duplicateStore;
        this.planStore = planStore;
        this.quarantineStore = quarantineStore;
        this.clock = clock;
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<ScanStore.ScanRow> scans = scanStore.list();
        result.put("totalScans", scans.size());
        scans.stream()
                .filter(s -> s.status() == ScanStatus.DONE)
                .max(Comparator.comparingLong(ScanStore.ScanRow::id))
                .ifPresentOrElse(latest -> {
                    result.put("latestScan", Map.of(
                            "id", latest.id(), "rootPath", latest.rootPath()));

                    List<FileMeta> files = fileRepository.byScan(latest.id());
                    result.put("indexedFiles", files.size());
                    result.put("indexedBytes", files.stream().mapToLong(FileMeta::size).sum());

                    Map<String, Long> categories = new TreeMap<>();
                    files.forEach(f -> categories.merge(
                            f.category() == null ? Category.OTHER.name() : f.category().name(), 1L, Long::sum));
                    result.put("categories", categories);

                    List<DuplicateStore.GroupView> groups = duplicateStore.groupsOf(latest.id()).stream()
                            .filter(g -> g.status() == DuplicateStore.GroupStatus.CONFIRMED)
                            .toList();
                    long reclaimable = groups.stream()
                            .mapToLong(g -> g.members().size() > 1
                                    ? g.totalSize() / g.members().size() * (g.members().size() - 1L) : 0)
                            .sum();
                    result.put("duplicateGroups", groups.size());
                    result.put("reclaimableBytes", reclaimable);
                }, () -> {
                    result.put("latestScan", null);
                    result.put("indexedFiles", 0);
                    result.put("indexedBytes", 0);
                    result.put("categories", Map.of());
                    result.put("duplicateGroups", 0);
                    result.put("reclaimableBytes", 0);
                });

        Map<String, Long> plansByStatus = new TreeMap<>();
        planStore.listPlans().forEach(p -> plansByStatus.merge(p.status().name(), 1L, Long::sum));
        result.put("plans", plansByStatus);

        List<QuarantineItem> quarantine = quarantineStore.active();
        result.put("quarantine", Map.of(
                "items", quarantine.size(),
                "bytes", quarantine.stream().mapToLong(QuarantineItem::size).sum(),
                "expired", quarantine.stream().filter(i ->
                        Instant.now(clock).isAfter(i.purgeAfter())).count()));

        return result;
    }
}
