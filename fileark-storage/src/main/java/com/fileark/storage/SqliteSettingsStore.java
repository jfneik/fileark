package com.fileark.storage;

import com.fileark.core.port.SettingsStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SqliteSettingsStore implements SettingsStore {

    private final Database db;

    public SqliteSettingsStore(Database db) {
        this.db = db;
    }

    @Override
    public Optional<String> get(String key) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void put(String key, String value) {
        try (var conn = db.open();
             var ps = conn.prepareStatement(
                     "INSERT INTO setting(key, value) VALUES (?, ?) " +
                             "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void remove(String key) {
        try (var conn = db.open();
             var ps = conn.prepareStatement("DELETE FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Map<String, String> all() {
        try (var conn = db.open();
             var rs = conn.createStatement().executeQuery("SELECT key, value FROM setting ORDER BY key")) {
            Map<String, String> map = new LinkedHashMap<>();
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
