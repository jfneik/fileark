package com.fileark.core.fixture;

import com.fileark.core.execute.UndoKind;
import com.fileark.core.execute.UndoRecord;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.RuleStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.rule.Rule;
import com.fileark.core.scan.ScanStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** 内存版仓储桩：core 单测与属性测试共用。 */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static class MemScanStore implements ScanStore {
        public final Map<Long, ScanRow> rows = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long create(String rootPath) {
            long id = seq.incrementAndGet();
            rows.put(id, new ScanRow(id, rootPath, ScanStatus.RUNNING, null, "now", null));
            return id;
        }

        @Override
        public Optional<ScanRow> find(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ScanRow> list() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void updateStatus(long id, ScanStatus status, String statsJson) {
            ScanRow r = rows.get(id);
            rows.put(id, new ScanRow(r.id(), r.rootPath(), status, statsJson, r.startedAt(), "now"));
        }
    }

    public static class MemFileRepository implements FileRepository {
        public final Map<Long, FileMeta> byId = new ConcurrentHashMap<>();
        private final Map<Long, String> sampleHashes = new ConcurrentHashMap<>();
        private final Map<Long, String> contentHashes = new ConcurrentHashMap<>();
        private final Map<Long, String> skipReasons = new ConcurrentHashMap<>();
        private final Map<Long, Long> scanOf = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public List<FileMeta> insertAll(long scanId, List<FileMeta> entries) {
            List<FileMeta> out = new ArrayList<>(entries.size());
            for (FileMeta f : entries) {
                long id = seq.incrementAndGet();
                FileMeta stored = new FileMeta(id, f.path(), f.size(), f.mtime(), f.mimeType(), f.category());
                byId.put(id, stored);
                scanOf.put(id, scanId);
                out.add(stored);
            }
            return out;
        }

        @Override
        public List<FileMeta> byScan(long scanId) {
            return scanOf.entrySet().stream()
                    .filter(e -> e.getValue() == scanId)
                    .map(e -> byId.get(e.getKey()))
                    .sorted(java.util.Comparator.comparing(f -> f.path().toString()))
                    .collect(Collectors.toList());
        }

        @Override
        public void updateSampleHash(long id, String sampleKey) {
            sampleHashes.put(id, sampleKey);
        }

        @Override
        public void updateContentHash(long id, String sha256) {
            contentHashes.put(id, sha256);
        }

        @Override
        public void markSkipped(long id, String reason) {
            skipReasons.put(id, reason);
        }

        @Override
        public java.util.Optional<String> findReusableContentHash(String path, long size, Instant mtime) {
            return byId.values().stream()
                    .filter(m -> m.path().toString().equals(path) && m.size() == size && m.mtime().equals(mtime))
                    .map(m -> contentHashes.get(m.id()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst();
        }

        /** 测试断言用。 */
        public String contentHash(long id) {
            return contentHashes.get(id);
        }
    }

    public static class MemDuplicateStore implements DuplicateStore {
        public record Row(long id, long scanId, String hash, GroupStatus status, List<Long> memberIds) {
        }

        public final Map<Long, Row> rows = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long createGroup(long scanId, String hash, GroupStatus status, List<Long> memberIds) {
            long id = seq.incrementAndGet();
            rows.put(id, new Row(id, scanId, hash, status, new ArrayList<>(memberIds)));
            return id;
        }

        @Override
        public void updateStatus(long groupId, GroupStatus status) {
            Row r = rows.get(groupId);
            rows.put(groupId, new Row(r.id(), r.scanId(), r.hash(), status, r.memberIds()));
        }

        @Override
        public void deleteGroup(long groupId) {
            rows.remove(groupId);
        }

        @Override
        public List<GroupView> groupsOf(long scanId) {
            return rows.values().stream()
                    .filter(r -> r.scanId() == scanId)
                    .map(this::toView)
                    .toList();
        }

        @Override
        public Optional<GroupView> findGroup(long groupId) {
            return Optional.ofNullable(rows.get(groupId)).map(this::toView);
        }

        private GroupView toView(Row r) {
            List<FileMeta> members = r.memberIds().stream()
                    .map(id -> new FileMeta(id, null, 0, null, null, null))
                    .toList();
            return new GroupView(r.id(), r.hash(), r.status(), 0, members);
        }
    }

    public static class MemRuleStore implements RuleStore {
        public final Map<Long, Rule> rules = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long create(Rule rule) {
            long id = seq.incrementAndGet();
            rules.put(id, new Rule(id, rule.name(), rule.priority(), rule.enabled(),
                    rule.conditions(), rule.action()));
            return id;
        }

        @Override
        public void update(Rule rule) {
            rules.put(rule.id(), rule);
        }

        @Override
        public void delete(long id) {
            rules.remove(id);
        }

        @Override
        public List<Rule> all() {
            return rules.values().stream()
                    .sorted(Comparator.comparingInt(Rule::priority))
                    .toList();
        }
    }

    public static class MemPlanStore implements PlanStore {
        public final Map<Long, PlanRow> plans = new ConcurrentHashMap<>();
        public final Map<Long, List<Operation>> ops = new ConcurrentHashMap<>();
        private final AtomicLong planSeq = new AtomicLong();
        private final AtomicLong opSeq = new AtomicLong();

        @Override
        public long create(PlanStatus status, String description, List<Operation> operations) {
            long id = planSeq.incrementAndGet();
            plans.put(id, new PlanRow(id, status, description, null, "now", null, null));
            List<Operation> withIds = new ArrayList<>(operations.size());
            for (Operation o : operations) {
                withIds.add(o.withId(opSeq.incrementAndGet()));
            }
            ops.put(id, withIds);
            return id;
        }

        @Override
        public Optional<PlanRow> findPlan(long id) {
            return Optional.ofNullable(plans.get(id));
        }

        @Override
        public List<PlanRow> listPlans() {
            return new ArrayList<>(plans.values());
        }

        @Override
        public List<Operation> operationsOf(long planId) {
            // 返回快照而非内部列表：仓储不得暴露可变内部状态，
            // 否则调用方迭代期间 store 更新状态会触发 ConcurrentModificationException
            return new ArrayList<>(ops.getOrDefault(planId, List.of()));
        }

        @Override
        public Optional<PlanRow> findExecutingPlan() {
            return plans.values().stream()
                    .filter(r -> r.status() == PlanStatus.EXECUTING)
                    .findFirst();
        }

        @Override
        public void updatePlanStatus(long planId, PlanStatus status, String error) {
            PlanRow r = plans.get(planId);
            plans.put(planId, new PlanRow(r.id(), status, r.description(), error,
                    r.createdAt(), status == PlanStatus.EXECUTED ? "now" : r.executedAt(),
                    status == PlanStatus.ROLLED_BACK ? "now" : r.rolledBackAt()));
        }

        @Override
        public void updateOperationStatus(long planId, long operationId, OperationStatus status, String error) {
            List<Operation> list = ops.get(planId);
            list.replaceAll(o -> o.id() == operationId
                    ? new Operation(o.id(), o.type(), o.src(), o.dst(), o.expectedSize(),
                    o.expectedMtime(), status, error) : o);
        }
    }

    public static class MemQuarantineStore implements com.fileark.core.port.QuarantineStore {
        public final Map<Long, com.fileark.core.quarantine.QuarantineItem> items = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long insert(com.fileark.core.quarantine.QuarantineItem item) {
            long id = seq.incrementAndGet();
            // 存回填 id 后的对象，保持与 SQLite 行为一致
            com.fileark.core.quarantine.QuarantineItem stored =
                    new com.fileark.core.quarantine.QuarantineItem(id, item.originalPath(),
                            item.quarantinePath(), item.size(), item.hash(),
                            item.quarantinedAt(), item.purgeAfter(), item.purged());
            items.put(id, stored);
            return id;
        }

        @Override
        public List<com.fileark.core.quarantine.QuarantineItem> active() {
            return items.values().stream()
                    .filter(i -> !i.purged())
                    .sorted(java.util.Comparator.comparing(
                            com.fileark.core.quarantine.QuarantineItem::quarantinedAt).reversed())
                    .toList();
        }

        @Override
        public Optional<com.fileark.core.quarantine.QuarantineItem> find(long id) {
            return Optional.ofNullable(items.get(id));
        }

        @Override
        public Optional<com.fileark.core.quarantine.QuarantineItem> findByQuarantinePath(String path) {
            return items.values().stream()
                    .filter(i -> i.quarantinePath().equals(path))
                    .findFirst();
        }

        @Override
        public void markPurged(long id) {
            com.fileark.core.quarantine.QuarantineItem i = items.get(id);
            items.put(id, new com.fileark.core.quarantine.QuarantineItem(
                    i.id(), i.originalPath(), i.quarantinePath(), i.size(), i.hash(),
                    i.quarantinedAt(), i.purgeAfter(), true));
        }

        @Override
        public void delete(long id) {
            items.remove(id);
        }
    }

    public static class MemUndoLogStore implements UndoLogStore {
        public final List<UndoRecord> records = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public synchronized void append(long planId, long operationId, UndoKind kind, String payloadJson) {
            records.add(new UndoRecord(seq.incrementAndGet(), planId, operationId, kind, payloadJson, false));
        }

        @Override
        public synchronized List<UndoRecord> forOperation(long planId, long operationId) {
            return records.stream()
                    .filter(r -> r.planId() == planId && r.operationId() == operationId)
                    .sorted(Comparator.comparingLong(UndoRecord::id).reversed())
                    .toList();
        }

        @Override
        public synchronized List<UndoRecord> pendingOfPlan(long planId) {
            return records.stream()
                    .filter(r -> r.planId() == planId && !r.applied())
                    .sorted(Comparator.comparingLong(UndoRecord::id).reversed())
                    .toList();
        }

        @Override
        public synchronized void markApplied(long planId, long operationId) {
            records.replaceAll(r -> r.planId() == planId && r.operationId() == operationId
                    ? new UndoRecord(r.id(), r.planId(), r.operationId(), r.kind(), r.payloadJson(), true)
                    : r);
        }
    }
}
