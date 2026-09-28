package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 操作结论：成功落账 / 被拒绝（拒绝原因另记）。 */
public enum OperationStatus {
    APPLIED,
    REJECTED
}
