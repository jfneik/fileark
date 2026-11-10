package com.fileark.core.port;

import java.util.Map;
import java.util.Optional;

/** 键值配置（排除目录、阈值、保留天数等）。 */
public interface SettingsStore {

    Optional<String> get(String key);

    void put(String key, String value);

    void remove(String key);

    Map<String, String> all();
}
