package com.fileark.core.port;

import com.fileark.core.quarantine.QuarantineItem;

import java.util.List;
import java.util.Optional;

public interface QuarantineStore {

    long insert(QuarantineItem item);

    /** 未清除（purged=false）的条目，按入区时间倒序。 */
    List<QuarantineItem> active();

    Optional<QuarantineItem> find(long id);

    /** 按隔离区路径定位条目（撤销日志回滚时用）。 */
    Optional<QuarantineItem> findByQuarantinePath(String quarantinePath);

    void markPurged(long id);

    void delete(long id);
}
