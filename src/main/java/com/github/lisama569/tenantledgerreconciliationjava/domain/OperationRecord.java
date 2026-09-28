package com.github.lisama569.tenantledgerreconciliationjava.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 操作记录：append-only 操作日志的一条记录，也是服务重启后状态重放的最小单元。
 *
 * <p>每个记录携带：</p>
 * <ul>
 *   <li>{@code seq}：单调递增序号，由存储层在落盘时分配。重放时据此识别乱序/缺口；</li>
 *   <li>{@code idempotencyKey}：业务幂等键。同一键的记录在重放中只生效一次，
 *       后续为重复记录（在线路径上表现为返回首次结果，绝不重复入账）；</li>
 *   <li>{@code status}：APPLIED / REJECTED。被拒绝的操作不产生账务影响，
 *       但仍保留记录以解释失败原因；</li>
 *   <li>{@code failureCode} / {@code failureReason}：可区分的拒绝原因；</li>
 *   <li>{@code payload}：指令内容（JSON），重放时按 type 解释；</li>
 *   <li>{@code checksum}：对 seq 行内容的 CRC32，检测中断/损坏写。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class OperationRecord {

    private long seq;
    private String tenantId;
    private String requestId;
    private String idempotencyKey;
    private String role;
    private OperationType type;
    private OperationStatus status;
    private FailureCode failureCode;
    private String failureReason;
    /** 指令负载 JSON（账户/金额/entryId/异常引用等），重放唯一可信输入。 */
    private String payload;
    private Long checksum;
    private long timestamp;

    public OperationRecord() {
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public OperationType getType() {
        return type;
    }

    public void setType(OperationType type) {
        this.type = type;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public void setStatus(OperationStatus status) {
        this.status = status;
    }

    public FailureCode getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(FailureCode failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Long getChecksum() {
        return checksum;
    }

    public void setChecksum(Long checksum) {
        this.checksum = checksum;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
