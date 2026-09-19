package com.fileark.core.quarantine;

import com.fileark.core.execute.FsFileOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantineManagerTest {

    @TempDir
    Path dir;

    @Test
    void admitRenamesAndRestoreRebuildsParents() throws IOException {
        Path quarantine = dir.resolve("quarantine");
        QuarantineManager manager = new QuarantineManager(quarantine, new FsFileOps(),
                Clock.systemUTC(), 30);
        Files.createDirectories(dir.resolve("old/deep"));
        Path src = Files.write(dir.resolve("old/deep/x.txt"), "X".getBytes());

        Path admitted = manager.admit(src);
        assertThat(Files.exists(admitted)).isTrue();
        assertThat(admitted.getParent()).isEqualTo(quarantine);
        assertThat(Files.exists(src)).isFalse();

        manager.restore(new QuarantineItem(1, src.toString(), admitted.toString(),
                1, null, Instant.now(), Instant.now().plus(Duration.ofDays(1)), false));
        assertThat(Files.exists(src)).isTrue(); // 原父目录被重建
        assertThat(Files.exists(admitted)).isFalse();
    }

    @Test
    void expiryRespectsRetentionDays() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneId.of("UTC"));
        QuarantineManager manager = new QuarantineManager(dir.resolve("q"), new FsFileOps(), clock, 30);
        Instant quarantined = Instant.parse("2026-08-01T00:00:00Z");
        Instant purgeAfter = manager.purgeAfter(quarantined);
        assertThat(manager.expired(new QuarantineItem(1, "o", "q", 1, null,
                quarantined, purgeAfter, false))).isTrue();
        assertThat(manager.expired(new QuarantineItem(1, "o", "q", 1, null,
                Instant.parse("2026-09-01T00:00:00Z"), manager.purgeAfter(Instant.parse("2026-09-01T00:00:00Z")), false)))
                .isFalse();
    }
}
