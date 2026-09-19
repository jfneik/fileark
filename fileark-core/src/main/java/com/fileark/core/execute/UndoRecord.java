package com.fileark.core.execute;

/** 一条撤销日志（从仓储读出的形态）。 */
public record UndoRecord(long id, long planId, long operationId, UndoKind kind,
                         String payloadJson, boolean applied) {
}
