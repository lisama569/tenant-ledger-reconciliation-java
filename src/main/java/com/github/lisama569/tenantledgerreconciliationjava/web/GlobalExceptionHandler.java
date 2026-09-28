package com.github.lisama569.tenantledgerreconciliationjava.web;

import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常映射：
 * - 业务失败 ServiceException -> 422，body 含 reason 可区分失败原因；
 * - 非法参数 IllegalArgumentException -> 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> handleService(ServiceException ex) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "reason", ex.getReason().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "reason", "INVALID_OPERATION",
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
