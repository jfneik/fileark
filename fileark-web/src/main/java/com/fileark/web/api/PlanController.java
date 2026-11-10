package com.fileark.web.api;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.execute.TransactionalExecutor;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.web.service.PlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanStore planStore;
    private final TransactionalExecutor executor;
    private final PlanService planService;

    public PlanController(PlanStore planStore, TransactionalExecutor executor, PlanService planService) {
        this.planStore = planStore;
        this.executor = executor;
        this.planService = planService;
    }

    /** 生成预览计划：不落库、不改盘（dry-run 与真实执行共用同一构建路径）。 */
    @PostMapping("/dry-run")
    public PlanDto dryRun(@Valid @RequestBody CreatePlanRequest request) {
        return PlanDto.of(planService.buildDraft(request));
    }

    /** 持久化计划（PLANNED），随后可执行。 */
    @PostMapping
    public PlanDto create(@Valid @RequestBody CreatePlanRequest request) {
        Plan draft = planService.buildDraft(request);
        long planId = planStore.create(PlanStatus.PLANNED, draft.description(), draft.operations());
        return PlanDto.of(new Plan(planId, PlanStatus.PLANNED, draft.description(), null,
                planStore.operationsOf(planId)));
    }

    @GetMapping
    public List<PlanRowDto> list() {
        return planStore.listPlans().stream().map(PlanRowDto::of).toList();
    }

    @GetMapping("/{id}")
    public PlanDto get(@PathVariable long id) {
        PlanStore.PlanRow row = planStore.findPlan(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + id));
        return new PlanDto(row.id(), row.status().name(), row.description(), row.error(),
                planStore.operationsOf(id).stream().map(OperationDto::of).toList());
    }

    @PostMapping("/{id}/execute")
    public PlanDto execute(@PathVariable long id) {
        return PlanDto.of(executor.execute(id));
    }

    @PostMapping("/{id}/rollback")
    public PlanDto rollback(@PathVariable long id) {
        return PlanDto.of(executor.rollback(id));
    }

    /** NEEDS_REVIEW 的人工闭环：用户核对完矛盾条目后标记，状态转终态 ACKNOWLEDGED。 */
    @PostMapping("/{id}/ack-review")
    public PlanDto ackReview(@PathVariable long id) {
        PlanStore.PlanRow row = planStore.findPlan(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + id));
        if (row.status() != PlanStatus.NEEDS_REVIEW) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "仅 NEEDS_REVIEW 状态可标记复核，当前状态: " + row.status());
        }
        planStore.updatePlanStatus(id, PlanStatus.ACKNOWLEDGED, "已人工复核");
        return get(id);
    }
}
