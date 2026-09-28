package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 操作的最终处理状态。 */
public enum OperationStatus {
    /** 指令校验通过并已入账/生效。 */
    APPLIED,
    /** 指令被拒绝（余额不足、租户不匹配、非法参数、无权限等），不产生账务影响。 */
    REJECTED
}
