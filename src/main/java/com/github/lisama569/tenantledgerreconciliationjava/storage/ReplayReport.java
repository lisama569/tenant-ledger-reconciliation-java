package com.github.lisama569.tenantledgerreconciliationjava.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 重放结果：启动重放操作日志时产生，用于明确识别重复、乱序、中断/损坏记录，
 * 保证任何异常都不会被静默吞掉。
 *
 * <ul>
 *   <li>{@code applied}/{@code rejected}：按首次出现生效的记录数；</li>
 *   <li>{@code duplicateKeys}：幂等键重复（安全跳过，只登记首次结果）；</li>
 *   <li>{@code outOfOrderSeqs}/{@code gapBefore}：序号乱序/缺口（缺号可能意味着日志丢失）；</li>
 *   <li>{@code corruptLines}：CRC 不匹配或无法解析的行（典型的写入中断/截断）；</li>
 *   <li>{@code truncatedTail}：日志末尾存在不完整写，安全丢弃并明确告警。</li>
 * </ul>
 */
public final class ReplayReport {

    private int applied;
    private int rejected;
    private final List<String> duplicateKeys = new ArrayList<>();
    private final List<String> outOfOrderSeqs = new ArrayList<>();
    private final List<String> gapWarnings = new ArrayList<>();
    private final List<String> corruptLines = new ArrayList<>();
    private boolean truncatedTail;

    public int getApplied() {
        return applied;
    }

    public void incApplied() {
        applied++;
    }

    public int getRejected() {
        return rejected;
    }

    public void incRejected() {
        rejected++;
    }

    public List<String> getDuplicateKeys() {
        return duplicateKeys;
    }

    public List<String> getOutOfOrderSeqs() {
        return outOfOrderSeqs;
    }

    public List<String> getGapWarnings() {
        return gapWarnings;
    }

    public List<String> getCorruptLines() {
        return corruptLines;
    }

    public boolean isTruncatedTail() {
        return truncatedTail;
    }

    public void setTruncatedTail(boolean truncatedTail) {
        this.truncatedTail = truncatedTail;
    }

    public List<String> getWarnings() {
        List<String> all = new ArrayList<>();
        duplicateKeys.forEach(k -> all.add("duplicate idempotencyKey skipped: " + k));
        outOfOrderSeqs.forEach(s -> all.add("out-of-order record: " + s));
        all.addAll(gapWarnings);
        corruptLines.forEach(l -> all.add("corrupt/truncated record skipped: " + l));
        if (truncatedTail) {
            all.add("truncated tail detected: last incomplete write discarded safely");
        }
        return all;
    }

    public boolean hasAnomaly() {
        return !(duplicateKeys.isEmpty() && outOfOrderSeqs.isEmpty()
                && gapWarnings.isEmpty() && corruptLines.isEmpty() && !truncatedTail);
    }
}
