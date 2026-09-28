package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 操作日志载荷：一条记录即一次业务请求的完整事实。
 */
public class OperationPayload {
    private String accountId;
    private String tenantId;
    private Direction direction;
    private long amount;
    private String referenceId;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
}
