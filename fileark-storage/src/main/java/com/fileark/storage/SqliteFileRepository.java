package com.fileark.storage;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.FileRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteFileRepository implements FileRepository {

    private final Database db;

    public SqliteFileRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<FileMeta> insertAll(long scanId, List<FileMeta> entries) {
        // 事务内逐条插入并取回自增 id——xerial 驱动对批量 executeBatch 后的
        // getGeneratedKeys 只可靠返回最后一行，逐条取键保证 id 与条目一一对应
        try (var conn = db.open()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO file_entry(scan_id, path, size, mtime, mime_type, category) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                List<FileMeta> out = new ArrayList<>(entries.size());
                for (FileMeta f : entries) {
                    ps.setLong(1, scanId);
                    ps.setString(2, f.path().toString());
                    ps.setLong(3, f.size());
                    ps.setString(4, f.mtime().toString());
                    ps.setString(5, f.mimeType());
                    ps.setString(6, f.category().name());
                    ps.executeUpdate();
                    long id;
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("file_entry 未返回生成主键");
                        }
                        id = keys.getLong(1);
                    }
                    out.add(new FileMeta(id, f.path(), f.size(), f.mtime(), f.mimeType(), f.category()));
                }
                conn.commit();
                return out;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<FileMeta> findById(long id) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("SELECT * FROM file_entry WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<FileMeta> byScan(long scanId) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "SELECT * FROM file_entry WHERE scan_id = ? ORDER BY path")) {
            ps.setLong(1, scanId);
            try (var rs = ps.executeQuery()) {
                List<FileMeta> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(map(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void updateSampleHash(long id, String sampleKey) {
        updateSingle("UPDATE file_entry SET sample_hash = ? WHERE id = ?", sampleKey, id);
    }

    @Override
    public void updateContentHash(long id, String sha256) {
        updateSingle("UPDATE file_entry SET content_hash = ? WHERE id = ?", sha256, id);
    }

    @Override
    public void markSkipped(long id, String reason) {
        updateSingle("UPDATE file_entry SET skip_reason = ? WHERE id = ?", reason, id);
    }

    @Override
    public Optional<String> findReusableContentHash(String path, long size, Instant mtime) {
        try (var conn = db.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT content_hash FROM file_entry " +
                             "WHERE path = ? AND size = ? AND mtime = ? AND content_hash IS NOT NULL " +
                             "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, path);
            ps.setLong(2, size);
            ps.setString(3, mtime.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<FileRepository.KnownHash> allKnownHashes() {
        // 单条 SQL 全量预取（同三元组只留最新一条：靠 MAX(id) 去重）
        String sql = "SELECT fe.path, fe.size, fe.mtime, fe.content_hash FROM file_entry fe " +
                "JOIN (SELECT MAX(id) AS id FROM file_entry WHERE content_hash IS NOT NULL " +
                "      GROUP BY path, size, mtime) latest ON latest.id = fe.id";
        try (var conn = db.open();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            List<FileRepository.KnownHash> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new FileRepository.KnownHash(rs.getString(1), rs.getLong(2),
                        Instant.parse(rs.getString(3)), rs.getString(4)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void updateSingle(String sql, String value, long id) {
        try (var conn = db.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileMeta map(ResultSet rs) throws SQLException {
        return new FileMeta(rs.getLong("id"), Path.of(rs.getString("path")),
                rs.getLong("size"), Instant.parse(rs.getString("mtime")),
                rs.getString("mime_type"), Category.valueOf(rs.getString("category")));
    }
}
