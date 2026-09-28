package com.fileark.web.api;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.port.ScanStore;
import com.fileark.core.port.SettingsStore;
import com.fileark.web.service.WatchManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watch")
public class WatchController {

    private final WatchManager manager;
    private final SettingsStore settings;

    public WatchController(WatchManager manager, SettingsStore settings) {
        this.manager = manager;
        this.settings = settings;
    }

    public record StartWatchRequest(String dir) {
    }

    public record WatchStatus(String dir, boolean active) {
    }

    @GetMapping
    public Map<String, Object> status() {
        List<String> activeDirs = manager.list();
        return Map.of(
                "active", activeDirs.stream().map(d -> new WatchStatus(d, true)).toList(),
                "rulesEnabled", manager.hasEnabledRules());
    }

    @PostMapping
    public Map<String, Object> start(@RequestBody StartWatchRequest request) {
        try {
            manager.start(Path.of(request.dir()));
        } catch (Exception e) {
            throw new FileArkException(ErrorCode.INVALID_PATH,
                    "无法启动监控: " + request.dir(), e.getMessage());
        }
        return Map.of("dir", request.dir(), "watching", true);
    }

    @DeleteMapping
    public Map<String, Object> stop(@RequestParam String dir) {
        manager.stop(Path.of(dir));
        // 清掉持久化里可能残留的失效绑定
        String saved = settings.get(WatchManager.SETTINGS_KEY).orElse("");
        settings.put(WatchManager.SETTINGS_KEY,
                saved.replace("," + dir, "").replace(dir + ",", "").replace(dir, ""));
        return Map.of("dir", dir, "watching", false);
    }
}
