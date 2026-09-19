package com.fileark.core.execute;

/** 预检发现的问题：携带问题码与可读原因，对应操作将被 SKIPPED。 */
public record PreflightIssue(com.fileark.core.exception.ErrorCode code, String message) {
}
