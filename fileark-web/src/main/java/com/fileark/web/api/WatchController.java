package com.fileark.web.api;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
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

    public WatchController(WatchManager manager) {
        this.manager = manager;
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
        // WatchManager.stop() 内部已重新持久化绑定列表，这里不要再动 settings
        // （曾在此对 settings 做字符串 replace，子串相同的目录会被误伤，已移除）
        manager.stop(Path.of(dir));
        return Map.of("dir", dir, "watching", false);
    }
}
