package com.fileark.core.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileHasherTest {

    @TempDir
    Path dir;

    @Test
    void emptyFileSha256() throws Exception {
        Path f = dir.resolve("empty.bin");
        Files.write(f, new byte[0]);
        assertThat(FileHasher.fullSha256(f))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void helloSha256() throws Exception {
        Path f = dir.resolve("hello.txt");
        Files.write(f, "hello".getBytes());
        assertThat(FileHasher.fullSha256(f))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sameContentDifferentNameSameSampleKey() throws Exception {
        byte[] small = "repeated content".getBytes();
        Path a = Files.write(dir.resolve("a.txt"), small);
        Path b = Files.write(dir.resolve("b.txt"), small);
        assertThat(FileHasher.sampleKey(a, small.length))
                .isEqualTo(FileHasher.sampleKey(b, small.length));
    }

    @Test
    void largeFileSampleKeyMatchesContent() throws Exception {
        // 20KB：超过抽样阈值，走头/中/尾采样
        byte[] payload = new byte[20_480];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Path a = Files.write(dir.resolve("a.bin"), payload);
        Path b = Files.write(dir.resolve("nested.bin"), payload);
        assertThat(FileHasher.sampleKey(a, payload.length))
                .isEqualTo(FileHasher.sampleKey(b, payload.length));

        byte[] altered = payload.clone();
        altered[payload.length / 2] ^= 0xFF; // 中段单字节不同
        Path c = Files.write(dir.resolve("c.bin"), altered);
        assertThat(FileHasher.sampleKey(c, payload.length))
                .isNotEqualTo(FileHasher.sampleKey(a, payload.length));
    }
}
