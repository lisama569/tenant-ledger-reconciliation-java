package com.github.lisama569.tenantledgerreconciliationjava.web;

import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把异常映射为带可区分失败码的 JSON 响应。
 *
 * <p>注意：命令类接口（开户/入账/标记/补偿）的业务拒绝在 200 响应体内以
 * {@code result=REJECTED} 表达（含幂等重放场景），这里只处理同步抛出的异常
 * （查询类失败、请求体不可解析等）。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, Object>> handleLedger(LedgerException e) {
        HttpStatus status = switch (e.getFailureCode()) {
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case ACCOUNT_NOT_FOUND, ENTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        log.warn("api rejected code={} reason={}", e.getFailureCode(), e.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "result", "REJECTED",
                "failureCode", e.getFailureCode().name(),
                "failureReason", e.getMessage() == null ? "" : e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "result", "REJECTED",
                "failureCode", "INVALID_REQUEST",
                "failureReason", "malformed JSON request body"));
    }
}
