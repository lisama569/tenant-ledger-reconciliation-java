package com.github.lisama569.tenantledgerreconciliationjava.service;

import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 操作日志哈希链工具：
 * hash_n = SHA-256(prevHash | seq | requestId | type | status | role | payload 的规范字符串)。
 * 任一字段被篡改或记录被调换，链上后续 hash 均无法吻合。
 */
public final class HashChains {

    public static final String GENESIS_HASH = "GENESIS";

    private HashChains() {
    }

    public static String hash(OperationRecord record, String prevHash) {
        String canonical = canonical(record);
        String input = String.valueOf(prevHash) + '|' + canonical;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 参与哈希的规范字段（replayFlag 不参与，因为它是重放派生标注）。 */
    private static String canonical(OperationRecord r) {
        OperationPayload p = r.getPayload();
        String payload = "null";
        if (p != null) {
            payload = String.join("~",
                    nz(p.getAccountId()),
                    nz(p.getTenantId()),
                    p.getDirection() == null ? "" : p.getDirection().name(),
                    Long.toString(p.getAmount()),
                    nz(p.getReferenceId()));
        }
        return String.join("|",
                Long.toString(r.getSeq()),
                nz(r.getRequestId()),
                r.getType() == null ? "" : r.getType().name(),
                r.getStatus() == null ? "" : r.getStatus().name(),
                r.getFailureReason() == null ? "" : r.getFailureReason().name(),
                r.getRequestedRole() == null ? "" : r.getRequestedRole().name(),
                payload);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
