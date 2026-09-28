package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 流水状态：正常流水计入余额；异常流水被隔离，不计入账户余额。 */
public enum EntryState {
    NORMAL,
    EXCEPTIONAL
}
