package com.fileark.core.execute;

import com.fileark.core.fixture.InMemoryStores;
import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.rule.ActionType;
import com.fileark.core.rule.Condition;
import com.fileark.core.rule.FieldType;
import com.fileark.core.rule.Operator;
import com.fileark.core.rule.Rule;
import com.fileark.core.rule.RuleAction;
import com.fileark.core.rule.RuleEngine;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Combinators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试（设计文档 §9 不变量 A/B）：
 * A. 任意 MOVE/RENAME 计划，执行后（无论成功还是自动回滚）再撤销，目录树必须逐字节还原。
 * B. 计划构建（dry-run 路径）绝不修改磁盘。
 */
class RollbackPropertiesTest {

    record Flat(String rel, String content) {
    }

    record TreeSpec(List<Flat> files) {
    }

    record MoveSpec(int fromIdx, int toIdx, int kind) {
    }

    // ---------- 随机源 ----------

    @Provide
    static Arbitrary<TreeSpec> trees() {
        Arbitrary<Integer> i = Arbitraries.integers().range(0, 4);
        Arbitrary<Integer> j = Arbitraries.integers().range(0, 4);
        Arbitrary<Integer> depth = Arbitraries.integers().range(0, 3);
        Arbitrary<String> rels = Combinators.combine(i, j, depth).as((a, b, d) -> switch (d) {
            case 0 -> "f" + a + "_" + b + ".txt";
            case 1 -> "d" + a + "/f" + b + ".txt";
            case 2 -> "d" + a + "/p" + b + ".png";
            default -> "d" + a + "/sub/f" + b + ".txt";
        });
        Arbitrary<String> contents = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
        Arbitrary<Flat> flats = Combinators.combine(rels, contents).as(Flat::new);
        return flats.list().ofMinSize(1).ofMaxSize(7).map(TreeSpec::new);
    }

    @Provide
    static Arbitrary<List<MoveSpec>> moveSpecs() {
        Arbitrary<Integer> from = Arbitraries.integers().range(0, 7);
        Arbitrary<Integer> to = Arbitraries.integers().range(0, 7);
        Arbitrary<Integer> kind = Arbitraries.integers().range(0, 1);
        return Combinators.combine(from, to, kind).as(MoveSpec::new)
                .list().ofMaxSize(5);
    }

    @Provide
    static Arbitrary<List<Rule>> rules() {
        Arbitrary<String> typeVal = Arbitraries.of("IMAGE", "DOCUMENT");
        Arbitrary<String> template = Arbitraries.of("{type}", "{type}/{yyyy}", "sorted");
        Arbitrary<Integer> prio = Arbitraries.integers().range(1, 9);
        return Combinators.combine(typeVal, template, prio).as((t, tpl, p) ->
                new Rule(0, "r" + p, p, true,
                        List.of(new Condition(FieldType.TYPE, Operator.EQ, t)),
                        new RuleAction(ActionType.MOVE, tpl, null))).list().ofMaxSize(3);
    }

    // ---------- 工具 ----------

    private Path materialize(Path root, TreeSpec tree) throws IOException {
        var seen = new java.util.HashSet<String>();
        for (Flat f : tree.files()) {
            if (!seen.add(f.rel())) {
                continue;
            }
            Path p = root.resolve(f.rel());
            Files.createDirectories(p.getParent());
            Files.write(p, f.content().getBytes());
        }
        return root;
    }

    private TreeMap<String, String> snapshot(Path root) throws IOException {
        TreeMap<String, String> snap = new TreeMap<>();
        Files.walk(root).sorted().forEach(p -> {
            String rel = root.relativize(p).toString();
            if (rel.isEmpty()) {
                return;
            }
            try {
                if (Files.isDirectory(p)) {
                    snap.put("D|" + rel, "");
                } else {
                    snap.put("F|" + rel, java.util.Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(p)));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return snap;
    }

    private FileMeta metaOf(Path p) throws IOException {
        Category category = p.toString().endsWith(".png") ? Category.IMAGE : Category.DOCUMENT;
        return new FileMeta(0, p, Files.size(p),
                Files.getLastModifiedTime(p).toInstant(), null, category);
    }

    // ---------- 不变量 A ----------

    @Property(tries = 100)
    void rollbackAlwaysRestoresTree(@ForAll("trees") TreeSpec tree,
                                    @ForAll("moveSpecs") List<MoveSpec> moves) throws Exception {
        Path base = Files.createTempDirectory("fileark-prop");
        Path root = base.resolve("root");
        Path quarantine = base.resolve("quarantine");
        try {
            materialize(root, tree);
            List<Path> files = snapshotPaths(root);
            TreeMap<String, String> before = snapshot(root);

            List<Operation> ops = new ArrayList<>();
            for (MoveSpec m : moves) {
                Path src = files.get(m.fromIdx() % files.size());
                Path dst = m.kind() == 0
                        ? src.resolveSibling("renamed_" + m.toIdx() + "_" + src.getFileName())
                        : root.resolve("mv" + m.toIdx()).resolve(src.getFileName());
                if (dst.equals(src)) {
                    continue;
                }
                ops.add(Operation.pending(com.fileark.core.plan.OperationType.MOVE, src, dst,
                        Files.size(src), Files.getLastModifiedTime(src).toInstant()));
            }
            if (ops.isEmpty()) {
                return;
            }

            PlanStore planStore = new InMemoryStores.MemPlanStore();
            UndoLogStore undoLogStore = new InMemoryStores.MemUndoLogStore();
            FsFileOps fs = new FsFileOps();
            TransactionalExecutor executor = new TransactionalExecutor(planStore, undoLogStore,
                    fs, new PreflightChecker(fs), quarantine);
            long planId = planStore.create(PlanStatus.PLANNED, "prop", ops);
            PlanStatus endStatus;
            try {
                endStatus = executor.execute(planId).status();
            } catch (com.fileark.core.exception.FileArkException e) {
                endStatus = PlanStatus.FAILED; // 已自动回滚
            }
            if (endStatus == PlanStatus.EXECUTED) {
                executor.rollback(planId);
            }
            assertThat(snapshot(root)).as("执行后必须可完全还原。终态=%s", endStatus).isEqualTo(before);
        } finally {
            deleteRecursively(base);
        }
    }

    // ---------- 不变量 B ----------

    @Property(tries = 100)
    void buildingPlanNeverTouchesDisk(@ForAll("trees") TreeSpec tree,
                                      @ForAll("rules") List<Rule> rules) throws Exception {
        Path base = Files.createTempDirectory("fileark-dry");
        Path root = base.resolve("root");
        try {
            materialize(root, tree);
            TreeMap<String, String> before = snapshot(root);

            List<FileMeta> metas = new ArrayList<>();
            for (Path p : snapshotPaths(root)) {
                metas.add(metaOf(p));
            }
            RuleEngine engine = new RuleEngine(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
            Planner planner = new Planner(engine, Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
            planner.fromRules(metas, rules, root, "dry-run 预览");

            assertThat(snapshot(root)).as("构建计划（dry-run）不得修改磁盘").isEqualTo(before);
        } finally {
            deleteRecursively(base);
        }
    }

    private List<Path> snapshotPaths(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
