package com.fileark.core.execute;

import com.fileark.core.fixture.InMemoryStores;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.Plan;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.quarantine.QuarantineManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalExecutorTest {

    @TempDir
    Path dir;

    Path quarantineDir;
    PlanStore planStore;
    UndoLogStore undoLogStore;
    TransactionalExecutor executor;
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        quarantineDir = dir.resolve("quarantine");
        planStore = new InMemoryStores.MemPlanStore();
        undoLogStore = new InMemoryStores.MemUndoLogStore();
        FsFileOps fs = new FsFileOps();
        executor = new TransactionalExecutor(planStore, undoLogStore, fs,
                new PreflightChecker(fs), quarantineDir);
    }

    private Operation moveOp(Path src, Path dst) throws IOException {
        return Operation.pending(com.fileark.core.plan.OperationType.MOVE, src, dst,
                Files.size(src), Files.getLastModifiedTime(src).toInstant());
    }

    @Test
    void successfulExecutionMovesFiles() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Path target = dir.resolve("out");
        Path dst = target.resolve("a.txt");

        long planId = planStore.create(PlanStatus.PLANNED, "整理", List.of(moveOp(a, dst)));
        Plan plan = executor.execute(planId);

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.exists(a)).isFalse();
        assertThat(plan.operations()).allMatch(o -> o.status() == OperationStatus.DONE);
    }

    @Test
    void faultAtKthOperationAutoRollsBack() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Path b = Files.write(dir.resolve("b.txt"), "B".getBytes());
        Path target = dir.resolve("out");
        Operation op1 = moveOp(a, target.resolve("a.txt"));
        Operation op2 = moveOp(b, target.resolve("b.txt"));

        FsFileOps fs = new FsFileOps();
        AtomicInteger moveCalls = new AtomicInteger();
        FileOps faulty = new FileOps() {
            @Override
            public boolean exists(Path p) {
                return fs.exists(p);
            }

            @Override
            public long size(Path p) throws IOException {
                return fs.size(p);
            }

            @Override
            public java.nio.file.attribute.FileTime mtime(Path p) throws IOException {
                return fs.mtime(p);
            }

            @Override
            public void move(Path src, Path dst) throws IOException {
                if (moveCalls.incrementAndGet() == 2) {
                    throw new IOException("注入故障：第 2 次 move");
                }
                fs.move(src, dst);
            }

            @Override
            public java.util.List<Path> mkdirs(Path d) throws IOException {
                return fs.mkdirs(d);
            }

            @Override
            public boolean deleteDirIfEmpty(Path d) throws IOException {
                return fs.deleteDirIfEmpty(d);
            }

            @Override
            public long usableSpace(Path root) {
                return fs.usableSpace(root);
            }
        };
        TransactionalExecutor failingExecutor = new TransactionalExecutor(
                planStore, undoLogStore, faulty, new PreflightChecker(faulty), quarantineDir);

        long planId = planStore.create(PlanStatus.PLANNED, "故障演练", List.of(op1, op2));
        assertThatThrownBy(() -> failingExecutor.execute(planId))
                .isInstanceOf(com.fileark.core.exception.FileArkException.class);

        assertThat(planStore.findPlan(planId).orElseThrow().status()).isEqualTo(PlanStatus.FAILED);
        // 两份文件都必须回到原位（第 1 条已成功 → 自动回滚；第 2 条从未执行）
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(b)).isTrue();
        // 第 1 条的撤销日志已应用；第 2 条未发生任何移动，日志也已收尾
        assertThat(undoLogStore.pendingOfPlan(planId)).allMatch(UndoRecord::applied);
    }

    @Test
    void preflightConflictSkipsOnlyTheConflictingOperation() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Path b = Files.write(dir.resolve("b.txt"), "B".getBytes());
        Path target = dir.resolve("out");
        Files.createDirectories(target);
        Files.write(target.resolve("a.txt"), "占位".getBytes()); // 制造目标冲突
        Operation op1 = moveOp(a, target.resolve("a.txt"));
        Operation op2 = moveOp(b, target.resolve("b.txt"));

        long planId = planStore.create(PlanStatus.PLANNED, "预检", List.of(op1, op2));
        Plan plan = executor.execute(planId);

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(plan.operations().get(0).status()).isEqualTo(OperationStatus.SKIPPED);
        assertThat(plan.operations().get(0).error()).contains("TARGET_CONFLICT");
        assertThat(plan.operations().get(1).status()).isEqualTo(OperationStatus.DONE);
        assertThat(Files.exists(a)).isTrue(); // 未动
        assertThat(Files.exists(target.resolve("b.txt"))).isTrue();
    }

    @Test
    void userRollbackRestoresEverything() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Path target = dir.resolve("deep/nested");
        Path dst = target.resolve("a.txt");

        long planId = planStore.create(PlanStatus.PLANNED, "撤销", List.of(moveOp(a, dst)));
        executor.execute(planId);
        assertThat(Files.exists(dst)).isTrue();

        Plan rolled = executor.rollback(planId);
        assertThat(rolled.status()).isEqualTo(PlanStatus.ROLLED_BACK);
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(dst)).isFalse();
        // 计划创建的空目录链应被逐级回收
        try (Stream<Path> list = Files.list(dir)) {
            assertThat(list).noneMatch(p -> p.getFileName().toString().equals("deep"));
        }
    }

    @Test
    void deleteToQuarantineIsReversible() throws Exception {
        Path a = Files.write(dir.resolve("a.txt"), "A".getBytes());
        Operation op = Operation.pending(com.fileark.core.plan.OperationType.DELETE_TO_QUARANTINE,
                a, null, Files.size(a), Files.getLastModifiedTime(a).toInstant());
        long planId = planStore.create(PlanStatus.PLANNED, "删除", List.of(op));

        Plan plan = executor.execute(planId);
        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(Files.exists(a)).isFalse();
        try (Stream<Path> q = Files.list(quarantineDir)) {
            assertThat(q).hasSize(1);
        }

        executor.rollback(planId);
        assertThat(Files.exists(a)).isTrue();
        try (Stream<Path> q = Files.list(quarantineDir)) {
            assertThat(q).isEmpty();
        }
    }
}
