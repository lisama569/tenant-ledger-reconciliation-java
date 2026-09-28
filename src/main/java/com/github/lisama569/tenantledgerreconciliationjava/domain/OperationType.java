package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 操作日志中的业务操作类型。 */
public enum OperationType {
    OPEN_ACCOUNT,
    ENTRY,
    MARK_ANOMALY,
    COMPENSATE
}
