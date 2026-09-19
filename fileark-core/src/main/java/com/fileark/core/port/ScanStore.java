package com.fileark.core.port;

import com.fileark.core.scan.ScanStatus;

import java.util.List;
import java.util.Optional;

public interface ScanStore {

    /** 新建扫描，状态 RUNNING，返回 id。 */
    long create(String rootPath);

    Optional<ScanRow> find(long id);

    List<ScanRow> list();

    /** 更新状态；statsJson 可为 null。 */
    void updateStatus(long id, ScanStatus status, String statsJson);

    record ScanRow(long id, String rootPath, ScanStatus status,
                   String statsJson, String startedAt, String finishedAt) {
    }
}
