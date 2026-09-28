package com.github.lisama569.tenantledgerreconciliationjava.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 操作指令负载（序列化为 {@link OperationRecord#getPayload()}）。
 *
 * <p>这是重放时唯一可信的指令输入；重放逻辑只依赖这里的字段，不依赖控制器对象。
 * 不同 {@link OperationType} 使用不同字段子集，未使用字段保持为 null。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class OperationPayload {

    private String accountId;
    private String entryId;
    private EntryType entryType;
    private Long amount;
    private Long initialBalance;
    /** MARK_EXCEPTION：关联事件号；COMPENSATE：幂等事件号（同一事件只补偿一次）。 */
    private String eventRef;
    /** COMPENSATION 流水号；补偿生效时创建。 */
    private String compensationEntryId;
    /** COMPENSATION 带符号金额（计入余额方向）。 */
    private Long compensationDelta;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Long getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(Long initialBalance) {
        this.initialBalance = initialBalance;
    }

    public String getEventRef() {
        return eventRef;
    }

    public void setEventRef(String eventRef) {
        this.eventRef = eventRef;
    }

    public String getCompensationEntryId() {
        return compensationEntryId;
    }

    public void setCompensationEntryId(String compensationEntryId) {
        this.compensationEntryId = compensationEntryId;
    }

    public Long getCompensationDelta() {
        return compensationDelta;
    }

    public void setCompensationDelta(Long compensationDelta) {
        this.compensationDelta = compensationDelta;
    }
}
