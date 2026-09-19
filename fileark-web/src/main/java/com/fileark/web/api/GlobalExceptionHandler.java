package com.fileark.web.api;

import com.fileark.core.exception.FileArkException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误体：{code, message, detail}。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FileArkException.class)
    public ResponseEntity<Map<String, String>> fileArk(FileArkException e) {
        return ResponseEntity.status(statusOf(e.code().name()))
                .body(Map.of("code", e.code().name(),
                        "message", String.valueOf(e.getMessage()),
                        "detail", String.valueOf(e.detail())));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_REQUEST",
                        "message", String.valueOf(e.getMessage()),
                        "detail", ""));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unknown(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "IO_ERROR",
                        "message", "服务器内部错误",
                        "detail", String.valueOf(e.getMessage())));
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "IO_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
    }
}
