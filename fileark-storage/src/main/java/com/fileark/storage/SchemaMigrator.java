package com.fileark.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内置版本化迁移器：按版本号顺序执行 classpath:db/migration/V{n}__desc.sql，
 * 已应用版本记录在 schema_version 表（见 docs 中 ADR：SQLite 属 Flyway 社区库，改用内置实现以消除脆弱依赖）。
 * 注意：SQL 文件内不允许使用分号注释或存储过程（当前 DDL 无此需求）。
 */
public final class SchemaMigrator {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    private final Database database;

    public SchemaMigrator(Database database) {
        this.database = database;
    }

    public void migrate() {
        List<Script> scripts = availableScripts();
        try (Connection conn = database.open()) {
            ensureVersionTable(conn);
            int applied = maxApplied(conn);
            for (Script script : scripts) {
                if (script.version() <= applied) {
                    continue;
                }
                execScript(conn, script);
                recordVersion(conn, script);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }
    }

    private record Script(int version, String name, String sql) {
    }

    private List<Script> availableScripts() {
        try (InputStream index = getClass().getResourceAsStream("/db/migration/index.txt")) {
            if (index == null) {
                throw new IllegalStateException("缺少 db/migration/index.txt 清单");
            }
            List<String> files = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))
                    .lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
            return files.stream().map(f -> {
                Matcher m = FILE_NAME.matcher(f);
                if (!m.matches()) {
                    throw new IllegalStateException("迁移脚本命名不合法: " + f);
                }
                return new Script(Integer.parseInt(m.group(1)), m.group(2), readClasspath("/db/migration/" + f));
            }).sorted(Comparator.comparingInt(Script::version)).toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移清单失败", e);
        }
    }

    private String readClasspath(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到迁移脚本: " + path);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败: " + path, e);
        }
    }

    private void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (" +
                    "version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT (datetime('now')))");
        }
    }

    private int maxApplied(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void execScript(Connection conn, Script script) throws SQLException {
        boolean oldAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String part : script.sql().split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(oldAutoCommit);
        }
    }

    private void recordVersion(Connection conn, Script script) throws SQLException {
        try (var ps = conn.prepareStatement("INSERT INTO schema_version(version, name) VALUES (?, ?)")) {
            ps.setInt(1, script.version());
            ps.setString(2, script.name());
            ps.executeUpdate();
        }
    }
}
