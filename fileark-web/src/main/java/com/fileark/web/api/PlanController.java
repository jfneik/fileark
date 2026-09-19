package com.fileark.web.api;

import com.fileark.core.dedup.KeepStrategy;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.model.FileMeta;
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
