package com.fileark.core.port;

import com.fileark.core.model.FileMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文件索引仓储。insertAll 必须为每个条目回填数据库生成的 id。 */
public interface FileRepository {

    List<FileMeta> insertAll(long scanId, List<FileMeta> entries);

    List<FileMeta> byScan(long scanId);

    /** 按 id 取单条档案（缩略图等按文件寻址的功能用）。 */
    Optional<FileMeta> findById(long id);

    void updateSampleHash(long id, String sampleKey);

    void updateContentHash(long id, String sha256);

    void markSkipped(long id, String reason);

    /**
     * 增量扫描（F10）：历史扫描中存在 path+size+mtime 完全一致且已算过全量哈希的条目，
     * 返回其内容哈希，本次扫描可跳过全量哈希。
     */
    Optional<String> findReusableContentHash(String path, long size, Instant mtime);

    /** 已有全量哈希的条目（跨全部历史扫描），供增量扫描一次性预取。 */
    List<KnownHash> allKnownHashes();

    /** path+size+mtime 三元组 → 内容哈希。 */
    record KnownHash(String path, long size, Instant mtime, String hash) {
        public String tripleKey() {
            return path + "|" + size + "|" + mtime;
        }
    }
}
