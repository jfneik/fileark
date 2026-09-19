package com.fileark.core.orchestrator;

import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.fixture.InMemoryStores;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.ScanStore;
import com.fileark.core.scan.ScanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ScanOrchestratorTest {

    @TempDir
    Path dir;

    @Test
    void twoPhaseScanFindsConfirmedDuplicates() throws Exception {
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7 % 251);
        }
        Files.write(dir.resolve("a.bin"), payload);
        Files.write(dir.resolve("b.bin"), payload);
        Files.write(dir.resolve("c.bin"), new byte[]{1, 2, 3, 4});

        InMemoryStores.MemScanStore scanStore = new InMemoryStores.MemScanStore();
        InMemoryStores.MemFileRepository repo = new InMemoryStores.MemFileRepository();
        InMemoryStores.MemDuplicateStore dupStore = new InMemoryStores.MemDuplicateStore();
        ExecutorService hashPool = Executors.newVirtualThreadPerTaskExecutor();
        ScanOrchestrator orchestrator = new ScanOrchestrator(
                scanStore, repo, dupStore, new DuplicateDetector(), hashPool, 4);

        long scanId = orchestrator.createScan(dir);
        orchestrator.runScan(scanId, dir, ScanEventListener.NOOP);

        assertThat(scanStore.find(scanId).orElseThrow().status()).isEqualTo(ScanStatus.DONE);
        var groups = dupStore.groupsOf(scanId);
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().status()).isEqualTo(DuplicateStore.GroupStatus.CONFIRMED);
        assertThat(groups.getFirst().members()).hasSize(2);
        // 两份重复文件都应有全量内容哈希
        assertThat(repo.byId.values().stream()
                .filter(m -> m.size() == payload.length)
                .map(FileMeta::id)
                .allMatch(id -> repo.contentHash(id) != null)).isTrue();
    }

    @Test
    void cancelFlagEndsScanAsCancelled() throws Exception {
        Files.write(dir.resolve("x.bin"), new byte[]{9, 9, 9, 9});
        ScanStore scanStore = new InMemoryStores.MemScanStore();
        ScanOrchestrator orchestrator = new ScanOrchestrator(scanStore,
                new InMemoryStores.MemFileRepository(), new InMemoryStores.MemDuplicateStore(),
                new DuplicateDetector(), Executors.newVirtualThreadPerTaskExecutor(), 2);

        long scanId = orchestrator.createScan(dir);
        orchestrator.runScan(scanId, dir, new ScanEventListener() {
            @Override
            public boolean cancelled() {
                return true;
            }
        });
        assertThat(scanStore.find(scanId).orElseThrow().status()).isEqualTo(ScanStatus.CANCELLED);
    }
}
