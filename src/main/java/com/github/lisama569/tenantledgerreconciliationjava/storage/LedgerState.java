package com.github.lisama569.tenantledgerreconciliationjava.storage;

import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * 落盘状态：操作日志是唯一持久化事实，派生状态（账户/流水）启动时重放重建。
 */
public class LedgerState {
    private long lastSeq;
    private String lastHash;
    private List<OperationRecord> operations = new ArrayList<>();

    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public String getLastHash() { return lastHash; }
    public void setLastHash(String lastHash) { this.lastHash = lastHash; }
    public List<OperationRecord> getOperations() { return operations; }
    public void setOperations(List<OperationRecord> operations) { this.operations = operations; }
}
