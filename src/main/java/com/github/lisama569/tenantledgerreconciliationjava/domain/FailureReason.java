package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 可区分的失败原因码。 */
public enum FailureReason {
    INVALID_OPERATION,
    INVALID_AMOUNT,
    UNKNOWN_ACCOUNT,
    ACCOUNT_CLOSED,
    TENANT_MISMATCH,
    INSUFFICIENT_FUNDS,
    DUPLICATE_REQUEST,
    PERMISSION_DENIED,
    ANOMALY_TARGET_MISSING,
    ENTRY_NOT_ANOMALY,
    ENTRY_ALREADY_COMPENSATED,
    LEDGER_UNHEALTHY
}
