package com.fileark.web.api;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.port.QuarantineStore;
import com.fileark.core.quarantine.QuarantineItem;
import com.fileark.core.quarantine.QuarantineManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quarantine")
public class QuarantineController {

    private final QuarantineStore store;
    private final QuarantineManager manager;

    public QuarantineController(QuarantineStore store, QuarantineManager manager) {
        this.store = store;
        this.manager = manager;
    }

    @GetMapping
    public List<QuarantineItem> list() {
        return store.active();
    }

    /** 恢复文件到原始路径（从隔离区移回，并删除条目）。 */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        QuarantineItem item = store.find(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "条目不存在: " + id));
        manager.restore(item);
        store.delete(id);
        return Map.of("id", id, "restored", true);
    }

    /** 一键清除全部已到期条目（UI 二次确认后调用）。 */
    @PostMapping("/purge-expired")
    public Map<String, Object> purgeExpired(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw new FileArkException(ErrorCode.INVALID_REQUEST, "彻底删除需要 confirm=true");
        }
        int purged = 0;
        for (QuarantineItem item : store.active()) {
            if (manager.expired(item)) {
                manager.purgePhysically(item);
                store.markPurged(item.id());
                purged++;
            }
        }
        return Map.of("purged", purged);
    }

    /** 彻底删除隔离文件（UI 需二次确认）。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> purge(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw new FileArkException(ErrorCode.INVALID_REQUEST, "彻底删除需要 confirm=true");
        }
        QuarantineItem item = store.find(id).orElseThrow(
                () -> new FileArkException(ErrorCode.INVALID_REQUEST, "条目不存在: " + id));
        manager.purgePhysically(item);
        store.markPurged(id);
        return Map.of("id", id, "purged", true);
    }
}
