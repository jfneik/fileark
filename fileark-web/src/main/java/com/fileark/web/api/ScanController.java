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
        // 支持 "~/Downloads" 这类写法：服务端展开 ~，前端快捷目录按钮才能工作
        Path root = com.fileark.web.config.AppProperties.expand(request.rootPath())
                .toAbsolutePath().normalize();
        // 防呆：磁盘根目录与整个用户主目录不属于合理的扫描范围
        if (root.getParent() == null) {
            throw new FileArkException(ErrorCode.INVALID_PATH,
                    "不能扫描磁盘根目录，请选择具体的工作目录");
        }
        String home = System.getProperty("user.home");
        if (root.toString().equals(home)) {
            throw new FileArkException(ErrorCode.INVALID_PATH,
                    "不要扫描整个用户主目录（范围过大且包含系统数据），请选择其中的子目录，如 ~/Downloads");
        }
        if (!Files.isDirectory(root)) {
            throw new FileArkException(ErrorCode.INVALID_PATH, "根目录不存在或不是目录: " + root);
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

    /** 导出重复报告 CSV（全部组，含状态列）：Excel/WPS 直接打开，用于留档或人工核对。 */
    @GetMapping("/{id}/duplicates/export")
    public ResponseEntity<byte[]> exportDuplicates(@PathVariable long id) {
        scanStore.find(id).orElseThrow(() ->
                new FileArkException(ErrorCode.INVALID_REQUEST, "扫描不存在: " + id));
        StringBuilder csv = new StringBuilder("group_id,hash,status,path,size_bytes,mtime\n");
        for (com.fileark.core.port.DuplicateStore.GroupView g : duplicateStore.groupsOf(id)) {
            for (com.fileark.core.model.FileMeta m : g.members()) {
                csv.append(g.id()).append(',')
                        .append(csvEscape(g.hash())).append(',')
                        .append(g.status()).append(',')
                        .append(csvEscape(m.path().toString())).append(',')
                        .append(m.size()).append(',')
                        .append(csvEscape(m.mtime() == null ? "" : m.mtime().toString()))
                        .append('\n');
            }
        }
        byte[] body = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=scan-" + id + "-duplicates.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    /** CSV 字段转义：含逗号/引号/换行时加引号，内部引号翻倍。 */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        ScanStore.ScanRow row = scanStore.find(id).orElseThrow(() ->
                new FileArkException(ErrorCode.INVALID_REQUEST, "扫描不存在: " + id));
        SseEmitter emitter = sseHub.subscribe(id);
        // 立即推送当前状态：订阅已结束的扫描时，前端能据此关闭连接而不是空等
        String phase = row.status() == com.fileark.core.scan.ScanStatus.RUNNING
                ? "SCANNING" : row.status().name();
        sseHub.send(id, "phase", phase);
        return emitter;
    }
}
