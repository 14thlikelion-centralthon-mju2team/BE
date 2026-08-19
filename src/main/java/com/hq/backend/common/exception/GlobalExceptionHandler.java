package com.hq.backend.common.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", Map.of(
                        "code", ex.getCode(),
                        "message", ex.getMessage(),
                        "retryable", false)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().get(0);
        String code = "email".equals(fieldError.getField()) ? "INVALID_EMAIL" : "INVALID_REQUEST";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of(
                        "code", code,
                        "message", fieldError.getDefaultMessage(),
                        "retryable", false)));
    }

    // enum 필드에 존재하지 않는 값이 오는 등, 요청 본문 자체를 역직렬화하지 못할 때
    // (검증 이전 단계라 MethodArgumentNotValidException보다 먼저 발생) 표준 포맷으로 응답한다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of(
                        "code", "INVALID_REQUEST",
                        "message", "요청 본문을 읽을 수 없습니다.",
                        "retryable", false)));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of(
                        "code", "INVALID_REQUEST",
                        "message", ex.getHeaderName() + " 헤더가 필요합니다.",
                        "retryable", false)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "INTERNAL_ERROR", "message", "서버 내부 오류가 발생했습니다."));
    }
}
