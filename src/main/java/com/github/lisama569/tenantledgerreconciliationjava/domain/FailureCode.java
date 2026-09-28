package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 可区分的失败原因。返回给调用方并写入操作记录，保证账务结论可解释。
 */
public enum FailureCode {
    NONE,
    INVALID_REQUEST,
    TENANT_MISMATCH,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ALREADY_EXISTS,
    INSUFFICIENT_FUNDS,
    ENTRY_NOT_FOUND,
    ENTRY_ALREADY_EXCEPTIONAL,
    PERMISSION_DENIED,
    COMPENSATION_ALREADY_APPLIED
}
