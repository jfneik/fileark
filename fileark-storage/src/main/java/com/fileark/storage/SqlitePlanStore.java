package com.fileark.storage;

import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.OperationType;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.port.PlanStore;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqlitePlanStore implements PlanStore {

    private final Database db;

    public SqlitePlanStore(Database db) {
        this.db = db;
    }

    @Override
    public long create(PlanStatus status, String description, List<Operation> ops) {
        try (var conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                long planId;
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plan(status, description, created_at) VALUES (?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, status.name());
                    ps.setString(2, description);
                    ps.setString(3, Instant.now().toString());
                    ps.executeUpdate();
                    planId = SqliteScanStore.generatedKey(ps);
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plan_operation(plan_id, seq, op_type, src, dst, expected_size, " +
                                "expected_mtime, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')")) {
                    for (int i = 0; i < ops.size(); i++) {
                        Operation op = ops.get(i);
                        ps.setLong(1, planId);
                        ps.setInt(2, i);
                        ps.setString(3, op.type().name());
                        ps.setString(4, op.src().toString());
                        ps.setString(5, op.dst() == null ? null : op.dst().toString());
                        ps.setLong(6, op.expectedSize());
                        ps.setString(7, op.expectedMtime() == null ? null : op.expectedMtime().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return planId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PlanRow> findPlan(long id) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("SELECT * FROM plan WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapPlan(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<PlanRow> listPlans() {
        try (var conn = db.open();
             var rs = conn.createStatement().executeQuery("SELECT * FROM plan ORDER BY id DESC")) {
            List<PlanRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapPlan(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<Operation> operationsOf(long planId) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "SELECT * FROM plan_operation WHERE plan_id = ? ORDER BY seq")) {
            ps.setLong(1, planId);
            try (var rs = ps.executeQuery()) {
                List<Operation> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(new Operation(rs.getLong("id"),
                            OperationType.valueOf(rs.getString("op_type")),
                            Path.of(rs.getString("src")),
                            rs.getString("dst") == null ? null : Path.of(rs.getString("dst")),
                            rs.getLong("expected_size"),
                            rs.getString("expected_mtime") == null ? null
                                    : Instant.parse(rs.getString("expected_mtime")),
                            OperationStatus.valueOf(rs.getString("status")),
                            rs.getString("error_msg")));
                }
                return ops;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PlanRow> findExecutingPlan() {
        try (var conn = db.open();
             var ps = conn.prepareStatement("SELECT * FROM plan WHERE status = 'EXECUTING' ORDER BY id LIMIT 1")) {
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapPlan(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updatePlanStatus(long planId, PlanStatus status, String error) {
        String sql = switch (status) {
            case EXECUTED -> "UPDATE plan SET status = ?, error_msg = ?, executed_at = ? WHERE id = ?";
            case ROLLED_BACK -> "UPDATE plan SET status = ?, error_msg = ?, rolled_back_at = ? WHERE id = ?";
            default -> "UPDATE plan SET status = ?, error_msg = ? WHERE id = ?";
        };
        try (var conn = db.open();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, error);
            if (status == PlanStatus.EXECUTED || status == PlanStatus.ROLLED_BACK) {
                ps.setString(3, Instant.now().toString());
                ps.setLong(4, planId);
            } else {
                ps.setLong(3, planId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updateOperationStatus(long planId, long operationId, OperationStatus status, String error) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "UPDATE plan_operation SET status = ?, error_msg = ? WHERE id = ? AND plan_id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, error);
            ps.setLong(3, operationId);
            ps.setLong(4, planId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlanRow mapPlan(java.sql.ResultSet rs) throws SQLException {
        return new PlanRow(rs.getLong("id"), PlanStatus.valueOf(rs.getString("status")),
                rs.getString("description"), rs.getString("error_msg"),
                rs.getString("created_at"), rs.getString("executed_at"), rs.getString("rolled_back_at"));
    }
}
