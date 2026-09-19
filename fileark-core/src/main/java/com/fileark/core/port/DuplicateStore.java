package com.fileark.core.port;

import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.model.FileMeta;

import java.util.List;
import java.util.Optional;

/** 重复组仓储。 */
public interface DuplicateStore {

    long createGroup(long scanId, String hash, GroupStatus status, List<Long> memberIds);

    void updateStatus(long groupId, GroupStatus status);

    /** 确认阶段疑似组被拆分/证伪后删除（连带成员关系）。 */
    void deleteGroup(long groupId);

    List<GroupView> groupsOf(long scanId);

    Optional<GroupView> findGroup(long groupId);

    enum GroupStatus {
        SUSPECTED, CONFIRMED
    }

    record GroupView(long id, String hash, GroupStatus status, long totalSize, List<FileMeta> members) {
    }
}
