package com.fileark.storage;

import com.fileark.core.execute.UndoKind;
import com.fileark.core.execute.UndoRecord;
import com.fileark.core.port.UndoLogStore;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SqliteUndoLogStore implements UndoLogStore {

    private final Database db;

    public SqliteUndoLogStore(Database db) {
        this.db = db;
    }

    @Override
    public void append(long planId, long operationId, UndoKind kind, String payloadJson) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "INSERT INTO undo_log(plan_id, operation_id, kind, payload_json, created_at) " +
                             "VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, planId);
            ps.setLong(2, operationId);
            ps.setString(3, kind.name());
            ps.setString(4, payloadJson);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<UndoRecord> forOperation(long planId, long operationId) {
        return query("SELECT * FROM undo_log WHERE plan_id = ? AND operation_id = ? ORDER BY id DESC",
                ps -> {
                    ps.setLong(1, planId);
                    ps.setLong(2, operationId);
                });
    }

    @Override
    public List<UndoRecord> pendingOfPlan(long planId) {
        return query("SELECT * FROM undo_log WHERE plan_id = ? AND applied = 0 ORDER BY id DESC",
                ps -> ps.setLong(1, planId));
    }

    @Override
    public void markApplied(long planId, long operationId) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "UPDATE undo_log SET applied = 1 WHERE plan_id = ? AND operation_id = ?")) {
            ps.setLong(1, planId);
            ps.setLong(2, operationId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private List<UndoRecord> query(String sql, Binder binder) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (var rs = ps.executeQuery()) {
                List<UndoRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(new UndoRecord(rs.getLong("id"), rs.getLong("plan_id"),
                            rs.getLong("operation_id"), UndoKind.valueOf(rs.getString("kind")),
                            rs.getString("payload_json"), rs.getInt("applied") == 1));
                }
                return records;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
