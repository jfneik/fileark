package com.fileark.core.dedup;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.scan.ScanListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectorTest {

    @TempDir
    Path dir;

    private final DuplicateDetector detector = new DuplicateDetector();

    @Test
    void findsSuspectedGroupAndConfirms() throws Exception {
        byte[] content = "identical payload 123".getBytes();
        Path a = Files.write(dir.resolve("a.txt"), content);
        Path b = Files.write(dir.resolve("b.txt"), content);
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Path c = Files.write(sub.resolve("copy.txt"), content);
        // 大小相同但内容不同：抽样阶段可能进桶，全量阶段必须被剔除
        byte[] other = "identical payload 456".getBytes();
        Path d = Files.write(dir.resolve("d.txt"), other);

        List<FileMeta> metas = new ArrayList<>();
        for (Path p : List.of(a, b, c, d)) {
            metas.add(new FileMeta(0, p, Files.size(p), Instant.EPOCH, "text/plain", Category.DOCUMENT));
        }

        List<DuplicateDetector.SuspectedGroup> suspected =
                detector.findSuspected(metas, ScanListener.NOOP);
        assertThat(suspected).hasSize(1);
        assertThat(suspected.getFirst().members()).hasSize(3);

        List<DuplicateDetector.ConfirmedGroup> confirmed =
                detector.confirm(suspected.getFirst(), ScanListener.NOOP);
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst().members())
                .extracting(FileMeta::path)
                .containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void emptyFilesAreIgnored() throws Exception {
        Path e1 = Files.write(dir.resolve("e1.bin"), new byte[0]);
        Path e2 = Files.write(dir.resolve("e2.bin"), new byte[0]);
        List<FileMeta> metas = List.of(
                new FileMeta(0, e1, 0, Instant.EPOCH, null, Category.OTHER),
                new FileMeta(0, e2, 0, Instant.EPOCH, null, Category.OTHER));
        assertThat(detector.findSuspected(metas, ScanListener.NOOP)).isEmpty();
    }
}
