package com.fileark.storage;

import com.fileark.core.port.ScanStore;
import com.fileark.core.scan.ScanStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteScanStore implements ScanStore {

    private final Database db;

    public SqliteScanStore(Database db) {
        this.db = db;
    }

    @Override
    public long create(String rootPath) {
        String now = java.time.Instant.now().toString();
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "INSERT INTO scan(root_path, status, started_at) VALUES (?, 'RUNNING', ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rootPath);
            ps.setString(2, now);
            ps.executeUpdate();
            return generatedKey(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<ScanRow> find(long id) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("SELECT * FROM scan WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<ScanRow> list() {
        try (var conn = db.open();
             var rs = conn.createStatement().executeQuery("SELECT * FROM scan ORDER BY id DESC")) {
            List<ScanRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updateStatus(long id, ScanStatus status, String statsJson) {
        boolean finished = status != ScanStatus.RUNNING;
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "UPDATE scan SET status = ?, stats_json = ?, finished_at = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, statsJson);
            ps.setString(3, finished ? java.time.Instant.now().toString() : null);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ScanRow map(ResultSet rs) throws SQLException {
        return new ScanRow(rs.getLong("id"), rs.getString("root_path"),
                ScanStatus.valueOf(rs.getString("status")), rs.getString("stats_json"),
                rs.getString("started_at"), rs.getString("finished_at"));
    }

    static long generatedKey(java.sql.Statement st) throws SQLException {
        try (var keys = st.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("未返回生成主键");
            }
            return keys.getLong(1);
        }
    }
}
