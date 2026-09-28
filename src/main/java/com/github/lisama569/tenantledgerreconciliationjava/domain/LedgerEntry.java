package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 账户流水：一笔资金变动的不可变业务事实。
 *
 * <ul>
 *   <li>{@code entryId} 全局唯一，由调用方提供，重复提交同一 entryId 会被拒绝（不是幂等成功，
 *       而是明确的 INVALID_REQUEST）；同一业务请求的重试必须复用 idempotencyKey。</li>
 *   <li>{@code signedDelta} 为带符号金额：DEBIT 为负、CREDIT/COMPENSATION 视方向而定。</li>
 *   <li>{@code state}=EXCEPTIONAL 的流水被隔离，不参与余额，但仍保留在流水总账中，
 *       配合补偿流水保持"账户 = 流水"的守恒关系（见对账规则）。</li>
 * </ul>
 */
public final class LedgerEntry {

    private final String tenantId;
    private final String accountId;
    private final String entryId;
    private final EntryType type;
    private final long signedDelta;
    private final String requestId;
    private final long createdAt;
    private EntryState state;
    /** 被标记异常时的事件号（MARK_EXCEPTION 产生）。 */
    private String exceptionRef;
    /** 关闭该异常的补偿事件号（COMPENSATE 产生）；非空表示差异已闭环。 */
    private String compensatedBy;

    public LedgerEntry(String tenantId, String accountId, String entryId, EntryType type,
                       long signedDelta, String requestId, long createdAt) {
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.entryId = entryId;
        this.type = type;
        this.signedDelta = signedDelta;
        this.requestId = requestId;
        this.createdAt = createdAt;
        this.state = EntryState.NORMAL;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getEntryId() {
        return entryId;
    }

    public EntryType getType() {
        return type;
    }

    public long getSignedDelta() {
        return signedDelta;
    }

    public String getRequestId() {
        return requestId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public EntryState getState() {
        return state;
    }

    public String getExceptionRef() {
        return exceptionRef;
    }

    public String getCompensatedBy() {
        return compensatedBy;
    }

    /** 补偿生效后关闭该异常流水（只能关闭一次）。 */
    public void closeWithCompensation(String compensationEventRef) {
        if (this.compensatedBy != null) {
            throw new IllegalStateException("entry " + entryId + " already compensated by "
                    + this.compensatedBy);
        }
        this.compensatedBy = compensationEventRef;
    }

    /** 在账户锁内把流水隔离为异常流水。只能从 NORMAL -> EXCEPTIONAL 一次。 */
    public void markExceptional(String exceptionRef) {
        if (this.state == EntryState.EXCEPTIONAL) {
            throw new LedgerException(FailureCode.ENTRY_ALREADY_EXCEPTIONAL,
                    "entry " + entryId + " already marked exceptional");
        }
        this.state = EntryState.EXCEPTIONAL;
        this.exceptionRef = exceptionRef;
    }
}
