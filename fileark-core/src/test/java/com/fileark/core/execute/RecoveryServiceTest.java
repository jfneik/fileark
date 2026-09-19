package com.fileark.core.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileark.core.fixture.InMemoryStores;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.UndoLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 模拟「执行到一半进程崩溃」后重启恢复。 */
class RecoveryServiceTest {

    @TempDir
    Path dir;

    PlanStore planStore;
    UndoLogStore undoLogStore;
    RecoveryService recovery;

    @BeforeEach
    void setUp() {
        planStore = new InMemoryStores.MemPlanStore();
        undoLogStore = new InMemoryStores.MemUndoLogStore();
        FsFileOps fs = new FsFileOps();
        TransactionalExecutor executor = new TransactionalExecutor(planStore, undoLogStore, fs,
                new PreflightChecker(fs), dir.resolve("quarantine"));
        recovery = new RecoveryService(planStore, undoLogStore, fs, executor);
    }

    @Test
    void recoversPartiallyExecutedPlan() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Path b = Files.write(dir.resolve("b.txt"), "B".getBytes());
        Path target = dir.resolve("out");
        Path aDst = target.resolve("a.txt");
        Path bDst = target.resolve("b.txt");

        long planId = planStore.create(PlanStatus.PLANNED, "崩溃演练", List.of(
                Operation.pending(com.fileark.core.plan.OperationType.MOVE, a, aDst,
                        Files.size(a), Files.getLastModifiedTime(a).toInstant()),
                Operation.pending(com.fileark.core.plan.OperationType.MOVE, b, bDst,
                        Files.size(b), Files.getLastModifiedTime(b).toInstant())));

        // 复现崩溃现场：第 1 条已完成（含撤销日志），进程在执行第 2 条前被杀
        planStore.updatePlanStatus(planId, PlanStatus.EXECUTING, null);
        undoLogStore.append(planId, planStore.operationsOf(planId).get(0).id(),
                UndoKind.MOVE_BACK, new ObjectMapper().writeValueAsString(
                        new UndoPayload(a.toString(), aDst.toString(), null, List.of(target.toString()))));
        Files.createDirectories(target);
        Files.move(a, aDst);
        planStore.updateOperationStatus(planId,
                planStore.operationsOf(planId).get(0).id(),
                com.fileark.core.plan.OperationStatus.DONE, null);

        int recovered = recovery.recoverOnStartup();

        assertThat(recovered).isEqualTo(1);
        assertThat(planStore.findPlan(planId).orElseThrow().status()).isEqualTo(PlanStatus.ROLLED_BACK);
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(aDst)).isFalse();
        assertThat(planStore.operationsOf(planId).get(1).status())
                .isEqualTo(com.fileark.core.plan.OperationStatus.SKIPPED);
    }

    @Test
    void noExecutingPlanIsNoOp() {
        assertThat(recovery.recoverOnStartup()).isZero();
    }
}
