package com.fileark.storage;

import com.fileark.core.execute.UndoKind;
import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.OperationType;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.PlanStore;
import com.fileark.core.scan.ScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SQLite 迁移 + 各仓储往返的最小冒烟测试。 */
class SqliteStoresRoundTripTest {

    @TempDir
    Path dir;

    Database db;
    SqliteScanStore scans;
    SqliteFileRepository files;
    SqliteDuplicateStore groups;
    SqlitePlanStore plans;
    SqliteUndoLogStore undo;
    SqliteQuarantineStore quarantine;
    SqliteSettingsStore settings;

    @BeforeEach
    void setUp() {
        db = new Database(dir.resolve("fileark.db"));
        new SchemaMigrator(db).migrate();
        scans = new SqliteScanStore(db);
        files = new SqliteFileRepository(db);
        groups = new SqliteDuplicateStore(db);
        plans = new SqlitePlanStore(db);
        undo = new SqliteUndoLogStore(db);
        quarantine = new SqliteQuarantineStore(db);
        settings = new SqliteSettingsStore(db);
    }

    @Test
    void scanFileDuplicateRoundTrip() throws Exception {
        long scanId = scans.create("/tmp/root");
        List<FileMeta> inserted = files.insertAll(scanId, List.of(
                new FileMeta(0, Path.of("/tmp/root/a.txt"), 100, Instant.EPOCH,
                        "text/plain", Category.DOCUMENT),
                new FileMeta(0, Path.of("/tmp/root/b.txt"), 100, Instant.EPOCH,
                        "text/plain", Category.DOCUMENT)));
        assertThat(inserted).allSatisfy(f -> assertThat(f.id()).isPositive());

        long groupId = groups.createGroup(scanId, "s100:abc", DuplicateStore.GroupStatus.SUSPECTED,
                List.of(inserted.get(0).id(), inserted.get(1).id()));
        DuplicateStore.GroupView view = groups.findGroup(groupId).orElseThrow();
        assertThat(view.members()).hasSize(2);
        assertThat(view.totalSize()).isEqualTo(200);

        groups.deleteGroup(groupId);
        assertThat(groups.groupsOf(scanId)).isEmpty();

        scans.updateStatus(scanId, ScanStatus.DONE, "{\"files\":2}");
        assertThat(scans.find(scanId).orElseThrow().status()).isEqualTo(ScanStatus.DONE);
    }

    @Test
    void planAndUndoRoundTrip() throws Exception {
        Operation op = Operation.pending(OperationType.MOVE, Path.of("/a/x.txt"),
                Path.of("/b/x.txt"), 10, Instant.EPOCH);
        long planId = plans.create(PlanStatus.PLANNED, "测试", List.of(op));

        plans.updatePlanStatus(planId, PlanStatus.EXECUTING, null);
        assertThat(plans.findExecutingPlan()).isPresent();

        long opId = plans.operationsOf(planId).getFirst().id();
        undo.append(planId, opId, UndoKind.MOVE_BACK, "{\"src\":\"/a/x.txt\",\"dst\":\"/b/x.txt\"}");
        assertThat(undo.forOperation(planId, opId)).hasSize(1);

        plans.updateOperationStatus(planId, opId, OperationStatus.DONE, null);
        assertThat(plans.operationsOf(planId).getFirst().status()).isEqualTo(OperationStatus.DONE);

        undo.markApplied(planId, opId);
        assertThat(undo.pendingOfPlan(planId)).isEmpty();

        plans.updatePlanStatus(planId, PlanStatus.EXECUTED, null);
        assertThat(plans.findExecutingPlan()).isEmpty();
        assertThat(plans.findPlan(planId).orElseThrow().status()).isEqualTo(PlanStatus.EXECUTED);
    }

    @Test
    void settingsUpsert() {
        settings.put("k", "v1");
        settings.put("k", "v2");
        assertThat(settings.get("k")).contains("v2");
        assertThat(settings.all()).containsEntry("k", "v2");
    }
}
