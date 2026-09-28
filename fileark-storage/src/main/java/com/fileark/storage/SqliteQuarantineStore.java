package com.fileark.storage;

import com.fileark.core.port.QuarantineStore;
import com.fileark.core.quarantine.QuarantineItem;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteQuarantineStore implements QuarantineStore {

    private final Database db;

    public SqliteQuarantineStore(Database db) {
        this.db = db;
    }

    @Override
    public long insert(QuarantineItem item) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "INSERT INTO quarantine_item(original_path, quarantine_path, size, hash, " +
                             "quarantined_at, purge_after) VALUES (?, ?, ?, ?, ?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, item.originalPath());
            ps.setString(2, item.quarantinePath());
            ps.setLong(3, item.size());
            ps.setString(4, item.hash());
            ps.setString(5, item.quarantinedAt().toString());
            ps.setString(6, item.purgeAfter().toString());
            ps.executeUpdate();
            return SqliteScanStore.generatedKey(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<QuarantineItem> active() {
        try (var conn = db.open();
             var rs = conn.createStatement().executeQuery(
                     "SELECT * FROM quarantine_item WHERE purged = 0 ORDER BY quarantined_at DESC")) {
            List<QuarantineItem> items = new ArrayList<>();
            while (rs.next()) {
                items.add(map(rs));
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<QuarantineItem> find(long id) {
        return queryOne("SELECT * FROM quarantine_item WHERE id = ?", ps -> ps.setLong(1, id));
    }

    @Override
    public Optional<QuarantineItem> findByQuarantinePath(String quarantinePath) {
        return queryOne("SELECT * FROM quarantine_item WHERE quarantine_path = ?",
                ps -> ps.setString(1, quarantinePath));
    }

    private Optional<QuarantineItem> queryOne(String sql, Binder binder) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Binder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    @Override
    public void markPurged(long id) {
        update("UPDATE quarantine_item SET purged = 1 WHERE id = ?", id);
    }

    @Override
    public void delete(long id) {
        update("DELETE FROM quarantine_item WHERE id = ?", id);
    }

    private void update(String sql, long id) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static QuarantineItem map(java.sql.ResultSet rs) throws SQLException {
        return new QuarantineItem(rs.getLong("id"), rs.getString("original_path"),
                rs.getString("quarantine_path"), rs.getLong("size"), rs.getString("hash"),
                Instant.parse(rs.getString("quarantined_at")), Instant.parse(rs.getString("purge_after")),
                rs.getInt("purged") == 1);
    }
}
