package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 账户流水（由操作日志确定性派生）。
 * amount 始终为正，方向由 direction 决定；referenceId 关联补偿时被补偿流水。
 */
public class LedgerEntry {
    private String entryId;
    private String accountId;
    private String tenantId;
    private Direction direction;
    private long amount;
    private String requestId;
    private String referenceId;
    private boolean anomaly;
    private boolean compensated;
    private boolean compensation;

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    public boolean isAnomaly() { return anomaly; }
    public void setAnomaly(boolean anomaly) { this.anomaly = anomaly; }
    public boolean isCompensated() { return compensated; }
    public void setCompensated(boolean compensated) { this.compensated = compensated; }
    public boolean isCompensation() { return compensation; }
    public void setCompensation(boolean compensation) { this.compensation = compensation; }
}
