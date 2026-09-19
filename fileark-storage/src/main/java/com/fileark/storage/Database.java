package com.fileark.storage;

import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite 连接源。WAL 模式 + busy_timeout；
 * 本项目为单用户本地应用，写入统一走短连接即可。
 */
public final class Database implements AutoCloseable {

    private final SQLiteDataSource dataSource;

    public Database(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + dbFile.getParent(), e);
        }
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    public Connection open() throws SQLException {
        Connection conn = dataSource.getConnection();
        try (var st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    @Override
    public void close() {
        // SQLiteDataSource 无需显式关闭
    }
}
