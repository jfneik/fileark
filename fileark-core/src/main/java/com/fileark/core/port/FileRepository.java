package com.fileark.core.port;

import com.fileark.core.model.FileMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文件索引仓储。insertAll 必须为每个条目回填数据库生成的 id。 */
public interface FileRepository {

    List<FileMeta> insertAll(long scanId, List<FileMeta> entries);

    List<FileMeta> byScan(long scanId);

    void updateSampleHash(long id, String sampleKey);

    void updateContentHash(long id, String sha256);

    void markSkipped(long id, String reason);

    /**
     * 增量扫描（F10）：历史扫描中存在 path+size+mtime 完全一致且已算过全量哈希的条目，
     * 返回其内容哈希，本次扫描可跳过全量哈希。
     */
    Optional<String> findReusableContentHash(String path, long size, Instant mtime);
}
