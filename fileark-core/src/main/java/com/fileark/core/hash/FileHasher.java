package com.fileark.core.hash;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件指纹工具：全量流式 SHA-256 与「头/中/尾」抽样哈希。
 * 抽样哈希的 key 携带文件大小，保证不同大小的文件即使摘要碰撞也不会归入同组。
 */
public final class FileHasher {

    public static final int CHUNK = 4096;
    /** 头/中/尾各 4KB；不足 3*CHUNK 的文件直接全量哈希。 */
    private static final int SAMPLE_THRESHOLD = 3 * CHUNK;

    private FileHasher() {
    }

    public static String fullSha256(Path file) {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            MessageDigest md = newSha256();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "读取文件失败: " + file, e.getMessage());
        }
    }

    /** 抽样指纹，格式 "s{size}:{digest}"，仅用于疑似分组，不作为内容最终凭证。 */
    public static String sampleKey(Path file, long size) {
        if (size < SAMPLE_THRESHOLD) {
            return "s" + size + ":" + fullSha256(file);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            MessageDigest md = newSha256();
            readChunk(md, ch, 0);
            readChunk(md, ch, (size - CHUNK) / 2);
            readChunk(md, ch, size - CHUNK);
            return "s" + size + ":" + HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new FileArkException(ErrorCode.IO_ERROR, "读取文件失败: " + file, e.getMessage());
        }
    }

    private static void readChunk(MessageDigest md, FileChannel ch, long position) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        ch.position(position);
        while (buf.hasRemaining() && ch.read(buf) != -1) {
            // 读满或读尽为止
        }
        md.update(buf.array(), 0, buf.position());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }
}
