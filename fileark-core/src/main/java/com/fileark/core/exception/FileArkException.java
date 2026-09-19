package com.fileark.core.exception;

public class FileArkException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;

    public FileArkException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public FileArkException(ErrorCode code, String message, String detail) {
        super(message);
        this.code = code;
        this.detail = detail;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
