package com.fileark.core.exception;

/** 业务错误码，Web 层据此映射 HTTP 状态。 */
public enum ErrorCode {
    TARGET_CONFLICT,
    SOURCE_CHANGED,
    INSUFFICIENT_SPACE,
    INVALID_PATH,
    INVALID_REQUEST,
    PLAN_NOT_EXECUTABLE,
    EXECUTION_FAILED,
    ROLLBACK_FAILED,
    SCAN_CANCELLED,
    QUARANTINE_CONFLICT,
    IO_ERROR
}
