package com.fileark.web.api;

import com.fileark.core.port.SettingsStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsStore settings;

    public SettingsController(SettingsStore settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, String> get() {
        return settings.all();
    }

    @PutMapping
    public Map<String, String> put(@RequestBody Map<String, String> kv) {
        kv.forEach(settings::put);
        return settings.all();
    }

    @DeleteMapping("/{key}")
    public Map<String, String> delete(@PathVariable String key) {
        settings.remove(key);
        return settings.all();
    }
}
