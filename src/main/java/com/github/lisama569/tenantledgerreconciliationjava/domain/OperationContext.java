package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 每次请求的调用上下文：调用方租户、角色、请求标识、幂等键。
 * 幂等键由调用方提供；重复提交（含并发）同一幂等键只会产生一次账务影响。
 */
public record OperationContext(
        String tenantId,
        String role,
        String requestId,
        String idempotencyKey
) {
}
