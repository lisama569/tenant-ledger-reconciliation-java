package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 账户（重放派生实体，初始余额作为 OPEN_ACCOUNT 操作的载荷被持久化）。
 */
public class Account {
    private String accountId;
    private String tenantId;
    private long openingBalance;
    private long balance;
    private boolean closed;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public long getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(long openingBalance) { this.openingBalance = openingBalance; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
}
