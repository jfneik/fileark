package com.fileark.core.port;

import com.fileark.core.model.FileMeta;

import java.util.List;

/** 文件索引仓储。insertAll 必须为每个条目回填数据库生成的 id。 */
public interface FileRepository {

    List<FileMeta> insertAll(long scanId, List<FileMeta> entries);

    List<FileMeta> byScan(long scanId);

    void updateSampleHash(long id, String sampleKey);

    void updateContentHash(long id, String sha256);

    void markSkipped(long id, String reason);
}
