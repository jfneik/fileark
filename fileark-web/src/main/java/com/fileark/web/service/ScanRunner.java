package com.fileark.web.service;

import com.fileark.core.orchestrator.ScanOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描任务执行器：把编排器放到虚拟线程上异步跑，
 * 取消标志由控制器写入、编排器在检查点读取（协作式取消）。
 */
@Component
public class ScanRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    private final ScanOrchestrator orchestrator;
    private final SseBridge bridge;
    private final ExecutorService scanPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public ScanRunner(ScanOrchestrator orchestrator, SseBridge bridge) {
        this.orchestrator = orchestrator;
        this.bridge = bridge;
    }

    public void submit(long scanId, Path root) {
        AtomicBoolean flag = new AtomicBoolean(false);
        cancelFlags.put(scanId, flag);
        scanPool.submit(() -> {
            try {
                orchestrator.runScan(scanId, root, bridge.listenerFor(scanId, flag));
            } catch (Exception e) {
                log.error("扫描 {} 失败", scanId, e);
            } finally {
                bridge.complete(scanId);
                cancelFlags.remove(scanId);
            }
        });
    }

    public void cancel(long scanId) {
        AtomicBoolean flag = cancelFlags.get(scanId);
        if (flag != null) {
            flag.set(true);
        }
    }
}
