package com.fileark.web.api;

import com.fileark.core.exception.FileArkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误体：{code, message, detail}。业务异常记 warn，未知异常必须记完整堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FileArkException.class)
    public ResponseEntity<Map<String, String>> fileArk(FileArkException e) {
        log.warn("业务异常: code={}, message={}, detail={}", e.code(), e.getMessage(), e.detail());
        return ResponseEntity.status(statusOf(e.code().name()))
                .body(Map.of("code", e.code().name(),
                        "message", String.valueOf(e.getMessage()),
                        "detail", String.valueOf(e.detail())));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        log.debug("请求参数不合法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_REQUEST",
                        "message", String.valueOf(e.getMessage()),
                        "detail", ""));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unknown(Exception e) {
        // 500 必须记录完整堆栈；对外只回固定文案，避免泄露内部实现细节
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "IO_ERROR",
                        "message", "服务器内部错误",
                        "detail", ""));
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case "INVALID_REQUEST", "INVALID_PATH" -> HttpStatus.BAD_REQUEST;
            case "IO_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
    }
}
