package com.fileark.core.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.OperationType;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.PlanStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 事务式文件操作执行器——本项目核心。
 * 不变式：任何 mutation 前必先落一条撤销日志；失败自动逆序回滚已成功条目。
 * 文件系统无事务，这里用「预检 + 撤销日志 + 状态机 + 启动重放」自实现。
 */
public final class TransactionalExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final com.fileark.core.port.PlanStore planStore;
    private final com.fileark.core.port.UndoLogStore undoLogStore;
    private final FileOps fileOps;
    private final PreflightChecker preflight;
    private final Path quarantineDir;

    public TransactionalExecutor(com.fileark.core.port.PlanStore planStore,
                                 com.fileark.core.port.UndoLogStore undoLogStore,
                                 FileOps fileOps,
                                 PreflightChecker preflight,
                                 Path quarantineDir) {
        this.planStore = planStore;
        this.undoLogStore = undoLogStore;
        this.fileOps = fileOps;
        this.preflight = preflight;
        this.quarantineDir = quarantineDir;
    }

    public Plan execute(long planId) {
        Plan plan = planStore.findPlan(planId)
                .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + planId));
        if (plan.status() != PlanStatus.PLANNED) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "计划不可执行，当前状态: " + plan.status());
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

        planStore.updatePlanStatus(planId, PlanStatus.EXECUTING, null);
        Deque<Operation> done = new ArrayDeque<>();
        try {
            for (Operation op : executable) {
                writeUndoBeforeMutation(planId, op);
                perform(op);
                planStore.updateOperationStatus(planId, op.id(), OperationStatus.DONE, null);
                done.push(op);
            }
            planStore.updatePlanStatus(planId, PlanStatus.EXECUTED, null);
        } catch (Exception e) {
            Operation failed = done.peek() == null ? executable.get(0) : executable.stream()
                    .filter(o -> !done.contains(o)).findFirst().orElse(executable.get(0));
            planStore.updateOperationStatus(planId, failed.id(), OperationStatus.FAILED, e.getMessage());
            rollbackDone(planId, done);
            planStore.updatePlanStatus(planId, PlanStatus.FAILED, e.getMessage());
            throw new FileArkException(ErrorCode.EXECUTION_FAILED,
                    "执行失败并已自动回滚: " + e.getMessage(), "planId=" + planId);
        }
        return planStore.findPlan(planId).orElseThrow();
    }

    /** 用户主动撤销：仅 EXECUTED 状态可撤销，逆序回滚全部 DONE 操作。 */
    public Plan rollback(long planId) {
        Plan plan = planStore.findPlan(planId)
                .orElseThrow(() -> new FileArkException(ErrorCode.INVALID_REQUEST, "计划不存在: " + planId));
        if (plan.status() != PlanStatus.EXECUTED) {
            throw new FileArkException(ErrorCode.PLAN_NOT_EXECUTABLE,
                    "仅 EXECUTED 状态可撤销，当前状态: " + plan.status());
        }
        Deque<Operation> done = new ArrayDeque<>();
        List<Operation> ops = planStore.operationsOf(planId);
        for (int i = ops.size() - 1; i >= 0; i--) {
            Operation op = ops.get(i);
            if (op.status() == OperationStatus.DONE) {
                done.push(op);
            }
        }
        rollbackDone(planId, done);
        planStore.updatePlanStatus(planId, PlanStatus.ROLLED_BACK, null);
        return planStore.findPlan(planId).orElseThrow();
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

    private void writeUndoBeforeMutation(long planId, Operation op) {
        UndoPayload payload = switch (op.type()) {
            case MOVE -> {
                List<Path> created = missingParents(op.dst().getParent());
                yield new UndoPayload(op.src().toString(), op.dst().toString(), null,
                        created.stream().map(Path::toString).toList());
            }
            case RENAME -> new UndoPayload(op.src().toString(), op.dst().toString(), null, List.of());
            case DELETE_TO_QUARANTINE -> new UndoPayload(op.src().toString(), null,
                    quarantineTarget(op.src()).toString(), List.of());
        };
        UndoKind kind = switch (op.type()) {
            case MOVE -> UndoKind.MOVE_BACK;
            case RENAME -> UndoKind.RENAME_BACK;
            case DELETE_TO_QUARANTINE -> UndoKind.RESTORE_FROM_QUARANTINE;
        };
        undoLogStore.append(planId, op.id(), kind, toJson(payload));
    }

    private void perform(Operation op) throws IOException {
        switch (op.type()) {
            case MOVE -> {
                fileOps.mkdirs(op.dst().getParent());
                fileOps.move(op.src(), op.dst());
            }
            case RENAME -> fileOps.move(op.src(), op.dst());
            case DELETE_TO_QUARANTINE -> {
                fileOps.mkdirs(quarantineDir);
                fileOps.move(op.src(), quarantineTarget(op.src()));
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
