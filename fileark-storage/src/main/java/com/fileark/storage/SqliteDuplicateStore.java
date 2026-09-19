package com.fileark.storage;

import com.fileark.core.model.FileMeta;
import com.fileark.core.port.DuplicateStore;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteDuplicateStore implements DuplicateStore {

    private final Database db;

    public SqliteDuplicateStore(Database db) {
        this.db = db;
    }

    @Override
    public long createGroup(long scanId, String hash, GroupStatus status, List<Long> memberIds) {
        try (var conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                long groupId;
                try (var ps = conn.prepareStatement(
                        "INSERT INTO duplicate_group(scan_id, hash, status, file_count, total_size) " +
                                "VALUES (?, ?, ?, 0, 0)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, scanId);
                    ps.setString(2, hash);
                    ps.setString(3, status.name());
                    ps.executeUpdate();
                    groupId = SqliteScanStore.generatedKey(ps);
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO duplicate_member(group_id, file_entry_id) VALUES (?, ?)")) {
                    for (Long memberId : memberIds) {
                        ps.setLong(1, groupId);
                        ps.setLong(2, memberId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (var ps = conn.prepareStatement(
                        "UPDATE duplicate_group SET file_count = " +
                                "(SELECT COUNT(*) FROM duplicate_member WHERE group_id = ?), " +
                                "total_size = (SELECT COALESCE(SUM(size), 0) FROM duplicate_member m " +
                                "JOIN file_entry f ON f.id = m.file_entry_id WHERE m.group_id = ?) " +
                                "WHERE id = ?")) {
                    ps.setLong(1, groupId);
                    ps.setLong(2, groupId);
                    ps.setLong(3, groupId);
                    ps.executeUpdate();
                }
                conn.commit();
                return groupId;
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
    public void updateStatus(long groupId, GroupStatus status) {
        exec("UPDATE duplicate_group SET status = ? WHERE id = ?", ps -> {
            ps.setString(1, status.name());
            ps.setLong(2, groupId);
        });
    }

    @Override
    public void deleteGroup(long groupId) {
        exec("DELETE FROM duplicate_group WHERE id = ?", ps -> ps.setLong(1, groupId));
    }

    @Override
    public List<GroupView> groupsOf(long scanId) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "SELECT id FROM duplicate_group WHERE scan_id = ? ORDER BY total_size DESC, id")) {
            ps.setLong(1, scanId);
            try (var rs = ps.executeQuery()) {
                List<GroupView> views = new ArrayList<>();
                while (rs.next()) {
                    views.add(findGroup(rs.getLong(1)).orElseThrow());
                }
                return views;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<GroupView> findGroup(long groupId) {
        try (var conn = db.open()) {
            var psGroup = conn.prepareStatement("SELECT * FROM duplicate_group WHERE id = ?");
            psGroup.setLong(1, groupId);
            try (var rs = psGroup.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String hash = rs.getString("hash");
                GroupStatus status = GroupStatus.valueOf(rs.getString("status"));
                long totalSize = rs.getLong("total_size");

                var psMembers = conn.prepareStatement(
                        "SELECT f.* FROM duplicate_member m JOIN file_entry f ON f.id = m.file_entry_id " +
                                "WHERE m.group_id = ? ORDER BY f.path");
                psMembers.setLong(1, groupId);
                List<FileMeta> members = new ArrayList<>();
                try (var rms = psMembers.executeQuery()) {
                    while (rms.next()) {
                        members.add(new FileMeta(rms.getLong("id"), Path.of(rms.getString("path")),
                                rms.getLong("size"), Instant.parse(rms.getString("mtime")),
                                rms.getString("mime_type"), com.fileark.core.model.Category.valueOf(rms.getString("category"))));
                    }
                }
                return Optional.of(new GroupView(groupId, hash, status, totalSize, members));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Binder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    private void exec(String sql, Binder binder) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
