package com.fileark.web.api;

import com.fileark.core.dedup.KeepStrategy;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.RuleStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.execute.TransactionalExecutor;
import com.fileark.core.plan.Planner;
import com.fileark.core.rule.Rule;
import com.fileark.core.scan.ScanStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final Planner planner;
    private final PlanStore planStore;
    private final TransactionalExecutor executor;
    private final DuplicateStore duplicateStore;
    private final RuleStore ruleStore;
    private final ScanStore scanStore;
    private final FileRepository fileRepository;

    public PlanController(Planner planner, PlanStore planStore, TransactionalExecutor executor,
                          DuplicateStore duplicateStore, RuleStore ruleStore,
                          ScanStore scanStore, FileRepository fileRepository) {
        this.planner = planner;
        this.planStore = planStore;
        this.executor = executor;
        this.duplicateStore = duplicateStore;
        this.ruleStore = ruleStore;
        this.scanStore = scanStore;
        this.fileRepository = fileRepository;
    }

    /** 生成预览计划：不落库、不改盘（dry-run 与真实执行共用同一构建路径）。 */
    @PostMapping("/dry-run")
    public Dto.PlanDto dryRun(@RequestBody Dto.CreatePlanRequest request) {
        return Dto.PlanDto.of(buildDraft(request));
    }

    /** 持久化计划（PLANNED），随后可执行。 */
    @PostMapping
    public Dto.PlanDto create(@RequestBody Dto.CreatePlanRequest request) {
        Plan draft = buildDraft(request);
        long planId = planStore.create(PlanStatus.PLANNED, draft.description(), draft.operations());
        return Dto.PlanDto.of(new Plan(planId, PlanStatus.PLANNED, draft.description(), null,
                planStore.operationsOf(planId)));
    }

    @GetMapping
    public List<Dto.PlanRowDto> list() {
        return planStore.listPlans().stream().map(Dto.PlanRowDto::of).toList();
    }

    @GetMapping("/{id}")
    public Dto.PlanDto get(@PathVariable long id) {
        PlanStore.PlanRow row = planStore.findPlan(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + id));
        return new Dto.PlanDto(row.id(), row.status().name(), row.description(), row.error(),
                planStore.operationsOf(id).stream().map(Dto.OperationDto::of).toList());
    }

    @PostMapping("/{id}/execute")
    public Dto.PlanDto execute(@PathVariable long id) {
        return Dto.PlanDto.of(executor.execute(id));
    }

    @PostMapping("/{id}/rollback")
    public Dto.PlanDto rollback(@PathVariable long id) {
        return Dto.PlanDto.of(executor.rollback(id));
    }

    /** NEEDS_REVIEW 的人工闭环：用户核对完矛盾条目后标记，状态转终态 ACKNOWLEDGED。 */
    @PostMapping("/{id}/ack-review")
    public Dto.PlanDto ackReview(@PathVariable long id) {
        PlanStore.PlanRow row = planStore.findPlan(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + id));
        if (row.status() != PlanStatus.NEEDS_REVIEW) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "仅 NEEDS_REVIEW 状态可标记复核，当前状态: " + row.status());
        }
        planStore.updatePlanStatus(id, PlanStatus.ACKNOWLEDGED, "已人工复核");
        return get(id);
    }

    private Plan buildDraft(Dto.CreatePlanRequest request) {
        if (request.refId() == null) {
            throw new FileArkException(ErrorCode.INVALID_REQUEST, "缺少 refId");
        }
        return switch (request.kind()) {
            case "DUPLICATE_CLEANUP" -> {
                DuplicateStore.GroupView group = duplicateStore.findGroup(request.refId())
                        .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST,
                                "重复组不存在: " + request.refId()));
                yield planner.fromDuplicateGroup(new com.fileark.core.dedup.DuplicateDetector.ConfirmedGroup(
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
                List<Operation> ops = new java.util.ArrayList<>();
                for (DuplicateStore.GroupView g : groups) {
                    ops.addAll(planner.fromDuplicateGroup(
                            new com.fileark.core.dedup.DuplicateDetector.ConfirmedGroup(g.hash(), g.members()),
                            strategy, "group#" + g.id()).operations());
                }
                yield new com.fileark.core.plan.Plan(0, PlanStatus.PLANNED,
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
