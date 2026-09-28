package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 业务操作类型（操作日志中记录的指令种类）。 */
public enum OperationType {
    CREATE_ACCOUNT,
    ENTRY,
    MARK_EXCEPTION,
    COMPENSATE
}
