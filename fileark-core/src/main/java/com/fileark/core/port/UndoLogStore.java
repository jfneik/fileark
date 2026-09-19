package com.fileark.core.port;

import com.fileark.core.execute.UndoKind;
import com.fileark.core.execute.UndoRecord;

import java.util.List;

/** 撤销日志仓储。同一条操作可能有多条记录（重试场景），取最新一条回滚。 */
public interface UndoLogStore {

    void append(long planId, long operationId, UndoKind kind, String payloadJson);

    /** 按写入时间倒序（最新在前）。 */
    List<UndoRecord> forOperation(long planId, long operationId);

    /** 该计划全部未 applied 的日志，按写入时间倒序。 */
    List<UndoRecord> pendingOfPlan(long planId);

    void markApplied(long planId, long operationId);
}
