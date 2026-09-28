package com.github.lisama569.tenantledgerreconciliationjava.storage;

/**
 * 一次已生效补偿的登记信息（用于对账报告解释差异闭环，以及"只能生效一次"的判定）。
 */
public record CompensationRecord(
        String tenantId,
        String accountId,
        String eventRef,
        long delta,
        String idempotencyKey
) {
}
