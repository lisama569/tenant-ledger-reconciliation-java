package com.github.lisama569.tenantledgerreconciliationjava.domain;

/**
 * 追加式操作日志的一条记录（系统的唯一事实来源）。
 * seq 为单调递增序号，prevHash/hash 构成防篡改哈希链。
 */
public class OperationRecord {
    private long seq;
    private String requestId;
    private OperationType type;
    private OperationStatus status;
    private FailureReason failureReason;
    private Role requestedRole;
    private OperationPayload payload;
    private String prevHash;
    private String hash;
    private ReplayFlag replayFlag;

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public OperationType getType() { return type; }
    public void setType(OperationType type) { this.type = type; }
    public OperationStatus getStatus() { return status; }
    public void setStatus(OperationStatus status) { this.status = status; }
    public FailureReason getFailureReason() { return failureReason; }
    public void setFailureReason(FailureReason failureReason) { this.failureReason = failureReason; }
    public Role getRequestedRole() { return requestedRole; }
    public void setRequestedRole(Role requestedRole) { this.requestedRole = requestedRole; }
    public OperationPayload getPayload() { return payload; }
    public void setPayload(OperationPayload payload) { this.payload = payload; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public ReplayFlag getReplayFlag() { return replayFlag; }
    public void setReplayFlag(ReplayFlag replayFlag) { this.replayFlag = replayFlag; }
}
