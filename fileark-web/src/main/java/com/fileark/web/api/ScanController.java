package com.fileark.web.api;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.orchestrator.ScanOrchestrator;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.ScanStore;
import com.fileark.web.service.ScanRunner;
import com.fileark.web.sse.SseHub;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scans")
public class ScanController {

    private final ScanOrchestrator orchestrator;
    private final ScanStore scanStore;
    private final DuplicateStore duplicateStore;
    private final ScanRunner scanRunner;
    private final SseHub sseHub;

    public ScanController(ScanOrchestrator orchestrator, ScanStore scanStore,
                          DuplicateStore duplicateStore, ScanRunner scanRunner, SseHub sseHub) {
        this.orchestrator = orchestrator;
        this.scanStore = scanStore;
        this.duplicateStore = duplicateStore;
        this.scanRunner = scanRunner;
        this.sseHub = sseHub;
    }

    public record CreateScanRequest(String rootPath) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateScanRequest request) {
        Path root = Path.of(request.rootPath());
        if (!Files.isDirectory(root)) {
            throw new FileArkException(ErrorCode.INVALID_PATH, "根目录不存在或不是目录: " + request.rootPath());
        }
        long scanId = orchestrator.createScan(root);
        scanRunner.submit(scanId, root);
        return Map.of("scanId", scanId, "status", "RUNNING");
    }

    @GetMapping
    public List<ScanStore.ScanRow> list() {
        return scanStore.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanStore.ScanRow> get(@PathVariable long id) {
        return ResponseEntity.of(scanStore.find(id));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        scanRunner.cancel(id);
        return Map.of("scanId", id, "cancelling", true);
    }

    @GetMapping("/{id}/duplicates")
    public List<Dto.GroupDto> duplicates(@PathVariable long id,
                                         @RequestParam(required = false) String status) {
        return duplicateStore.groupsOf(id).stream()
                .filter(g -> status == null || g.status().name().equalsIgnoreCase(status))
                .map(Dto.GroupDto::of)
                .toList();
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        scanStore.find(id).orElseThrow(() ->
                new FileArkException(ErrorCode.INVALID_REQUEST, "扫描不存在: " + id));
        return sseHub.subscribe(id);
    }
}
