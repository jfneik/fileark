package com.fileark.web.service;

import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.dedup.KeepStrategy;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.Planner;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.RuleStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.rule.Rule;
import com.fileark.core.scan.ScanStatus;
import com.fileark.web.api.CreatePlanRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 计划构建领域服务：dry-run 与真实执行共用同一构建路径，业务分支集中于此，保持接口层薄。 */
@Service
public class PlanService {

    private final Planner planner;
    private final PlanStore planStore;
    private final DuplicateStore duplicateStore;
    private final RuleStore ruleStore;
    private final ScanStore scanStore;
    private final FileRepository fileRepository;

    public PlanService(Planner planner, PlanStore planStore, DuplicateStore duplicateStore,
                       RuleStore ruleStore, ScanStore scanStore, FileRepository fileRepository) {
        this.planner = planner;
        this.planStore = planStore;
        this.duplicateStore = duplicateStore;
        this.ruleStore = ruleStore;
        this.scanStore = scanStore;
        this.fileRepository = fileRepository;
    }

    /** 按请求类型构建计划草稿（不落库、不改盘）。 */
    public Plan buildDraft(CreatePlanRequest request) {
        if (request.refId() == null) {
            throw new FileArkException(ErrorCode.INVALID_REQUEST, "缺少 refId");
        }
        return switch (request.kind()) {
            case "DUPLICATE_CLEANUP" -> {
                DuplicateStore.GroupView group = duplicateStore.findGroup(request.refId())
                        .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST,
                                "重复组不存在: " + request.refId()));
                yield planner.fromDuplicateGroup(new DuplicateDetector.ConfirmedGroup(
                                group.hash(), group.members()),
                        request.keepStrategyOr(KeepStrategy.NEWEST),
                        "清理重复组 #" + request.refId() + "（保留 " + request.keepStrategyOr(KeepStrategy.NEWEST) + "）");
            }
            // 一键清理：本次扫描全部确认重复组合并为一个计划，一次预检一次执行，可整体撤销
            case "DUPLICATE_CLEANUP_ALL" -> {
                List<DuplicateStore.GroupView> groups = duplicateStore.groupsOf(request.refId()).stream()
                        .filter(g -> g.status() == DuplicateStore.GroupStatus.CONFIRMED)
                        .toList();
                if (groups.isEmpty()) {
                    throw new FileArkException(ErrorCode.INVALID_REQUEST,
                            "扫描 #" + request.refId() + " 没有可清理的确认重复组");
                }
                KeepStrategy strategy = request.keepStrategyOr(KeepStrategy.NEWEST);
                List<Operation> ops = new ArrayList<>();
                for (DuplicateStore.GroupView g : groups) {
                    ops.addAll(planner.fromDuplicateGroup(
                            new DuplicateDetector.ConfirmedGroup(g.hash(), g.members()),
                            strategy, "group#" + g.id()).operations());
                }
                yield new Plan(0, PlanStatus.PLANNED,
                        "一键清理扫描 #" + request.refId() + " 的 " + groups.size()
                                + " 个重复组（保留 " + strategy + "）", null, ops);
            }
            case "RULE_APPLY" -> {
                if (request.targetDir() == null || request.targetDir().isBlank()) {
                    throw new FileArkException(ErrorCode.INVALID_REQUEST, "RULE_APPLY 需要 targetDir");
                }
                Rule rule = ruleStore.all().stream()
                        .filter(r -> r.id() == request.refId())
                        .findFirst()
                        .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST,
                                "规则不存在: " + request.refId()));
                Path targetDir = Path.of(request.targetDir());
                List<FileMeta> files = fileRepository.byScan(latestScanId())
                        .stream()
                        .filter(f -> f.path().startsWith(targetDir))
                        .toList();
                if (files.isEmpty()) {
                    throw new FileArkException(ErrorCode.INVALID_REQUEST,
                            "目标目录 " + targetDir + " 在最近的扫描索引中没有文件，请先扫描该目录");
                }
                yield planner.fromRules(files, List.of(rule), targetDir,
                        "应用规则「" + rule.name() + "」于 " + targetDir);
            }
            default -> throw new FileArkException(ErrorCode.INVALID_REQUEST, "未知计划类型: " + request.kind());
        };
    }

    private long latestScanId() {
        return scanStore.list().stream()
                .filter(r -> r.status() == ScanStatus.DONE)
                .max(Comparator.comparingLong(ScanStore.ScanRow::id))
                .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST,
                        "尚无已完成的扫描，请先扫描目标目录"))
                .id();
    }
}
