package com.fileark.core.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.scan.ScanStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 崩溃恢复：启动时发现 EXECUTING 状态的计划，按撤销日志重放回滚。
 * 磁盘实况与日志矛盾的条目不强行处理，计划进入 NEEDS_REVIEW 等人工确认。
 */
public final class RecoveryService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanStore planStore;
    private final UndoLogStore undoLogStore;
    private final FileOps fileOps;
    private final TransactionalExecutor executor;
    private final ScanStore scanStore;

    public RecoveryService(PlanStore planStore, UndoLogStore undoLogStore,
                           FileOps fileOps, TransactionalExecutor executor, ScanStore scanStore) {
        this.planStore = planStore;
        this.undoLogStore = undoLogStore;
        this.fileOps = fileOps;
        this.executor = executor;
        this.scanStore = scanStore;
    }

    /** @return 恢复的计划数；未发现 EXECUTING 计划时为 0。 */
    public int recoverOnStartup() {
        return planStore.findExecutingPlan().map(this::recover).orElse(0);
    }

    /**
     * 扫描任务恢复：单用户单进程应用，启动时仍处于 RUNNING 的扫描必然来自
     * 上一个已死进程——标记 FAILED，避免 UI 永远显示「扫描中」。
     */
    public int recoverStaleScans() {
        int fixed = 0;
        for (ScanStore.ScanRow row : scanStore.list()) {
            if (row.status() == ScanStatus.RUNNING) {
                try {
                    scanStore.updateStatus(row.id(), ScanStatus.FAILED,
                            JSON.writeValueAsString(Map.of("error", "进程中断：上次运行未完成")));
                    fixed++;
                } catch (java.io.IOException ignored) {
                    scanStore.updateStatus(row.id(), ScanStatus.FAILED, null);
                    fixed++;
                }
            }
        }
        return fixed;
    }

    private int recover(PlanStore.PlanRow row) {
        List<Operation> ops = planStore.operationsOf(row.id());
        Deque<Operation> rollbackable = new ArrayDeque<>();
        boolean contradiction = false;
        for (Operation op : ops) {
            switch (op.status()) {
                case DONE -> {
                    if (hasUndoAndConsistent(row.id(), op)) {
                        rollbackable.push(op);
                    } else {
                        contradiction = true;
                    }
                }
                case PENDING -> planStore.updateOperationStatus(row.id(), op.id(),
                        OperationStatus.SKIPPED, "崩溃时未执行");
                default -> {
                    // SKIPPED / FAILED 保持原状
                }
            }
        }
        try {
            if (!rollbackable.isEmpty()) {
                // 借用执行器的回滚路径：先标 EXECUTED 再 rollback，
                // 保证与用户手动撤销走完全相同的代码路径
                planStore.updatePlanStatus(row.id(), PlanStatus.EXECUTED, null);
                executor.rollback(row.id());
                if (contradiction) {
                    planStore.updatePlanStatus(row.id(), PlanStatus.NEEDS_REVIEW,
                            "已回滚可核对的条目；存在无法核对的条目，需人工复核");
                }
            } else {
                planStore.updatePlanStatus(row.id(),
                        contradiction ? PlanStatus.NEEDS_REVIEW : PlanStatus.ROLLED_BACK,
                        contradiction ? "存在无法核对的条目，需人工复核" : null);
            }
        } catch (Exception e) {
            planStore.updatePlanStatus(row.id(), PlanStatus.NEEDS_REVIEW,
                    "自动恢复未完成: " + e.getMessage());
        }
        return 1;
    }

    private boolean hasUndoAndConsistent(long planId, Operation op) {
        List<UndoRecord> records = undoLogStore.forOperation(planId, op.id());
        if (records.isEmpty()) {
            return false;
        }
        try {
            UndoPayload p = JSON.readValue(records.getFirst().payloadJson(), UndoPayload.class);
            return switch (records.getFirst().kind()) {
                case MOVE_BACK, RENAME_BACK ->
                        fileOps.exists(Path.of(p.dst())) || fileOps.exists(Path.of(p.src()));
                case RESTORE_FROM_QUARANTINE ->
                        fileOps.exists(Path.of(p.quarantinePath())) || fileOps.exists(Path.of(p.src()));
            };
        } catch (IOException e) {
            return false;
        }
    }
}
