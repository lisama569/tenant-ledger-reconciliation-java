package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 流水类型。 */
public enum EntryType {
    /** 借方：扣减余额（signedDelta 为负数）。 */
    DEBIT,
    /** 贷方：增加余额（signedDelta 为正数）。 */
    CREDIT,
    /** 补偿专用：用于修复对账差异的手工调整流水。 */
    COMPENSATION
}
