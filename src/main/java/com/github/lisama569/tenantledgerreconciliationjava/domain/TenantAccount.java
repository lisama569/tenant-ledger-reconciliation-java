package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 租户账户。
 *
 * <p>余额 {@code balance} 以最小货币单位的长整数表示（例如分），所有变动均为整数，
 * 杜绝浮点误差。余额恒等于该账户全部 NORMAL 流水的 signedDelta 之和；
 * EXCEPTIONAL 流水被隔离，不计入余额。</p>
 */
public final class TenantAccount {

    private final String tenantId;
    private final String accountId;
    private long balance;
    private final long createdAt;

    public TenantAccount(String tenantId, String accountId, long initialBalance, long createdAt) {
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.balance = initialBalance;
        this.createdAt = createdAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getBalance() {
        return balance;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** 在账户锁内应用一笔带符号变动。不做任何校验，调用方负责余额约束。 */
    public void applySignedDelta(long signedDelta) {
        this.balance += signedDelta;
    }

    /**
     * 仅供重放/故障注入使用：直接把余额设置为重建值。
     * 正常在线请求路径禁止调用。
     */
    public void resetBalanceForReplay(long value) {
        this.balance = value;
    }
}
