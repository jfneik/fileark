package com.fileark.core.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.OperationType;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.QuarantineStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.quarantine.QuarantineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事务式文件操作执行器——本项目核心。
 * 不变式：任何 mutation 前必先落一条撤销日志；失败自动逆序回滚已成功条目。
 * 文件系统无事务，这里用「预检 + 撤销日志 + 状态机 + 启动重放」自实现。
 */
public final class TransactionalExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransactionalExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanStore planStore;
    private final UndoLogStore undoLogStore;
    private final QuarantineStore quarantineStore;
    private final FileOps fileOps;
    private final PreflightChecker preflight;
    private final Path quarantineDir;
    private final Clock clock;
    private final long retentionDays;
    /** 计划级锁：撤销操作按 planId 串行化，防止并发双击导致重复回滚。 */
    private final Map<Long, Object> planLocks = new ConcurrentHashMap<>();

    public TransactionalExecutor(PlanStore planStore,
                                 UndoLogStore undoLogStore,
                                 QuarantineStore quarantineStore,
                                 FileOps fileOps,
                                 PreflightChecker preflight,
                                 Path quarantineDir,
                                 Clock clock,
                                 long retentionDays) {
        this.planStore = planStore;
        this.undoLogStore = undoLogStore;
        this.quarantineStore = quarantineStore;
        this.fileOps = fileOps;
        this.preflight = preflight;
        this.quarantineDir = quarantineDir;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    public Plan execute(long planId) {
        Plan plan = loadPlan(planId);
        if (plan.status() != PlanStatus.PLANNED) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "计划不可执行，当前状态: " + plan.status());
        }
        // 状态迁移用 CAS 抢占：并发重复提交时仅一个请求能把 PLANNED 迁移为 EXECUTING
        if (!planStore.tryUpdatePlanStatus(planId, PlanStatus.PLANNED, PlanStatus.EXECUTING, null)) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE, "计划正在执行中，请勿重复提交");
        }
        List<Operation> ops = planStore.operationsOf(planId);
        Map<Long, PreflightIssue> issues = preflight.check(ops);
        for (Operation op : ops) {
            PreflightIssue issue = issues.get(op.id());
            if (issue != null) {
                planStore.updateOperationStatus(planId, op.id(), OperationStatus.SKIPPED,
                        issue.code() + ": " + issue.message());
            }
        }
        List<Operation> executable = ops.stream().filter(o -> !issues.containsKey(o.id())).toList();
        if (executable.isEmpty()) {
            planStore.updatePlanStatus(planId, PlanStatus.FAILED, "所有操作未通过预检");
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE, "所有操作未通过预检，计划已标记 FAILED");
        }

        Deque<Operation> done = new ArrayDeque<>();
        try {
            for (Operation op : executable) {
                // 安全区目标路径只生成一次：撤销日志与实际移动必须指向同一路径
                Path quarantineTarget = op.type() == OperationType.DELETE_TO_QUARANTINE
                        ? quarantineTarget(op.src()) : null;
                writeUndoBeforeMutation(planId, op, quarantineTarget);
                perform(op, quarantineTarget);
                planStore.updateOperationStatus(planId, op.id(), OperationStatus.DONE, null);
                done.push(op);
            }
            planStore.updatePlanStatus(planId, PlanStatus.EXECUTED, null);
        } catch (Exception e) {
            Operation failed = done.peek() == null ? executable.get(0) : executable.stream()
                    .filter(o -> !done.contains(o)).findFirst().orElse(executable.get(0));
            planStore.updateOperationStatus(planId, failed.id(), OperationStatus.FAILED, e.getMessage());
            try {
                cleanupFailedOp(planId, failed);
                rollbackDone(planId, done);
            } catch (Exception rollbackError) {
                planStore.updatePlanStatus(planId, PlanStatus.NEEDS_REVIEW,
                        "执行失败且回滚未完成，需人工复核: " + rollbackError.getMessage());
                throw new FileArkException(ErrorCode.ROLLBACK_FAILED,
                        "执行失败且自动回滚未完成，已转入人工复核", "planId=" + planId);
            }
            planStore.updatePlanStatus(planId, PlanStatus.FAILED, e.getMessage());
            throw new FileArkException(ErrorCode.EXECUTION_FAILED,
                    "执行失败并已自动回滚: " + e.getMessage(), "planId=" + planId);
        }
        return loadPlan(planId);
    }

    /** 用户主动撤销：仅 EXECUTED 状态可撤销，逆序回滚全部 DONE 操作。 */
    public Plan rollback(long planId) {
        // 计划级锁：并发双击撤销时串行化，防止同一批 DONE 操作被重复回滚
        synchronized (planLocks.computeIfAbsent(planId, k -> new Object())) {
            return doRollback(planId);
        }
    }

    private Plan doRollback(long planId) {
        Plan plan = loadPlan(planId);
        if (plan.status() != PlanStatus.EXECUTED) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "仅 EXECUTED 状态可撤销，当前状态: " + plan.status());
        }
        Deque<Operation> done = new ArrayDeque<>();
        List<Operation> ops = planStore.operationsOf(planId);
        // 正序遍历 + 头插 = 弹出时逆序（LIFO）：后做的操作先撤销，
        // 否则先撤销的条目回收目录时会遇到后做条目的文件，空目录清理被中断
        for (Operation op : ops) {
            if (op.status() == OperationStatus.DONE) {
                done.push(op);
            }
        }
        rollbackDone(planId, done);
        planStore.updatePlanStatus(planId, PlanStatus.ROLLED_BACK, null);
        return loadPlan(planId);
    }

    /** 端口返回行记录，领域层用它重组完整的 Plan 聚合。 */
    private Plan loadPlan(long planId) {
        PlanStore.PlanRow row = planStore.findPlan(planId)
                .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + planId));
        return new Plan(row.id(), row.status(), row.description(), row.error(),
                planStore.operationsOf(planId));
    }

    /** 对已成功操作逆序应用撤销日志。任一条失败不中断其余回滚，最后聚合报错。 */
    private void rollbackDone(long planId, Deque<Operation> done) {
        List<String> failures = new ArrayList<>();
        while (!done.isEmpty()) {
            Operation op = done.pop();
            List<UndoRecord> records = undoLogStore.forOperation(planId, op.id());
            if (records.isEmpty()) {
                failures.add("缺少撤销日志 op=" + op.id());
                continue;
            }
            UndoRecord record = records.getFirst();
            try {
                applyUndo(record);
                undoLogStore.markApplied(planId, op.id());
            } catch (Exception e) {
                failures.add("op=" + op.id() + " 回滚失败: " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new FileArkException(ErrorCode.ROLLBACK_FAILED, "回滚存在失败项，需人工复核",
                    String.join("; ", failures));
        }
    }

    /**
     * 失败条目的收尾：它可能已建好目标目录但没完成移动。确认没有实际文件移动后，
     * 回收新建的空目录并把它的撤销日志标记为已应用，避免留下永远 pending 的日志。
     */
    private void cleanupFailedOp(long planId, Operation failed) {
        List<UndoRecord> records = undoLogStore.forOperation(planId, failed.id());
        if (records.isEmpty()) {
            return;
        }
        UndoRecord record = records.getFirst();
        try {
            UndoPayload p = fromJson(record.payloadJson());
            boolean mutated = switch (record.kind()) {
                case MOVE_BACK, RENAME_BACK -> fileOps.exists(Path.of(p.dst()));
                case RESTORE_FROM_QUARANTINE -> fileOps.exists(Path.of(p.quarantinePath()));
            };
            if (mutated) {
                return; // 移动已发生但状态未落库：交给 RecoveryService 恢复，不能擅自清理
            }
            if (p.createdDirs() != null) {
                List<String> dirs = new ArrayList<>(p.createdDirs());
                dirs.sort((a, b) -> Integer.compare(b.length(), a.length()));
                for (String d : dirs) {
                    if (!fileOps.deleteDirIfEmpty(Path.of(d))) {
                        break;
                    }
                }
            }
            undoLogStore.markApplied(planId, failed.id());
        } catch (IOException e) {
            // 收尾失败不影响主流程：撤销日志保留待人工核对，但必须留痕
            log.warn("失败条目收尾未完成 planId={}，撤销日志保留待人工核对", planId, e);
        }
    }

    private void writeUndoBeforeMutation(long planId, Operation op, Path quarantineTarget) {
        UndoPayload payload = switch (op.type()) {
            case MOVE -> {
                List<Path> created = missingParents(op.dst().getParent());
                yield new UndoPayload(op.src().toString(), op.dst().toString(), null,
                        created.stream().map(Path::toString).toList());
            }
            case RENAME -> new UndoPayload(op.src().toString(), op.dst().toString(), null, List.of());
            case DELETE_TO_QUARANTINE -> new UndoPayload(op.src().toString(), null,
                    quarantineTarget.toString(), List.of());
        };
        UndoKind kind = switch (op.type()) {
            case MOVE -> UndoKind.MOVE_BACK;
            case RENAME -> UndoKind.RENAME_BACK;
            case DELETE_TO_QUARANTINE -> UndoKind.RESTORE_FROM_QUARANTINE;
        };
        undoLogStore.append(planId, op.id(), kind, toJson(payload));
    }

    private void perform(Operation op, Path quarantineTarget) throws IOException {
        switch (op.type()) {
            case MOVE -> {
                fileOps.mkdirs(op.dst().getParent());
                fileOps.move(op.src(), op.dst());
            }
            case RENAME -> fileOps.move(op.src(), op.dst());
            case DELETE_TO_QUARANTINE -> {
                fileOps.mkdirs(quarantineDir);
                fileOps.move(op.src(), quarantineTarget);
                // 安全区登记：让隔离文件出现在安全区列表，可恢复、到期可清除
                quarantineStore.insert(new QuarantineItem(
                        0, op.src().toString(), quarantineTarget.toString(),
                        op.expectedSize(), null,
                        Instant.now(clock),
                        Instant.now(clock).plus(Duration.ofDays(retentionDays)),
                        false));
            }
        }
    }

    private void applyUndo(UndoRecord record) throws IOException {
        UndoPayload p = fromJson(record.payloadJson());
        switch (record.kind()) {
            case MOVE_BACK -> {
                if (fileOps.exists(Path.of(p.dst()))) {
                    fileOps.mkdirs(Path.of(p.src()).getParent());
                    fileOps.move(Path.of(p.dst()), Path.of(p.src()));
                    if (p.createdDirs() != null) {
                        List<String> dirs = new ArrayList<>(p.createdDirs());
                        dirs.sort((a, b) -> Integer.compare(b.length(), a.length()));
                        for (String d : dirs) {
                            if (!fileOps.deleteDirIfEmpty(Path.of(d))) {
                                break;
                            }
                        }
                    }
                }
            }
            case RENAME_BACK -> {
                if (fileOps.exists(Path.of(p.dst()))) {
                    fileOps.move(Path.of(p.dst()), Path.of(p.src()));
                }
            }
            case RESTORE_FROM_QUARANTINE -> {
                if (fileOps.exists(Path.of(p.quarantinePath()))) {
                    fileOps.mkdirs(Path.of(p.src()).getParent());
                    fileOps.move(Path.of(p.quarantinePath()), Path.of(p.src()));
                    quarantineStore.findByQuarantinePath(p.quarantinePath())
                            .ifPresent(i -> quarantineStore.delete(i.id()));
                }
            }
        }
    }

    private List<Path> missingParents(Path dir) {
        List<Path> missing = new ArrayList<>();
        Path cur = dir;
        while (cur != null && !fileOps.exists(cur)) {
            missing.add(0, cur);
            cur = cur.getParent();
        }
        return missing;
    }

    private Path quarantineTarget(Path src) {
        String name = src.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot) : "";
        return quarantineDir.resolve(UUID.randomUUID() + ext);
    }

    private static String toJson(UndoPayload p) {
        try {
            return JSON.writeValueAsString(p);
        } catch (IOException e) {
            throw new IllegalStateException("撤销载荷序列化失败", e);
        }
    }

    private static UndoPayload fromJson(String json) {
        try {
            return JSON.readValue(json, UndoPayload.class);
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "撤销载荷解析失败", json);
        }
    }
}
