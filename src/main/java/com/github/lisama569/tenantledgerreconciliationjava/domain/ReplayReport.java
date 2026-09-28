package com.github.lisama569.tenantledgerreconciliationjava.domain;

import java.util.List;

/**
 * 重放报告：说明本次重放处理了多少条、跳过多少重复、识别多少乱序/断链/缺号。
 * healthy=false 时账本进入只读保护（LEDGER_UNHEALTHY）。
 */
public record ReplayReport(
        int totalRecords,
        int applied,
        int skippedDuplicates,
        int outOfOrder,
        boolean gapDetected,
        boolean tampered,
        Long firstGapAfterSeq,
        Long tamperedSeq,
        boolean healthy,
        List<String> notes
) {
}
