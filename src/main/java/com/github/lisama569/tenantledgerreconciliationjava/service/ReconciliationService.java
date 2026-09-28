package com.github.lisama569.tenantledgerreconciliationjava.service;

import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryState;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReconciliationReport;
import com.github.lisama569.tenantledgerreconciliationjava.domain.TenantAccount;
import com.github.lisama569.tenantledgerreconciliationjava.storage.CompensationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对账查询服务（只读，不产生任何账务影响）。
 *
 * <p>按租户对每个账户核对核心守恒不变式：</p>
 * <pre>
 *     balance == Σ signedDelta (state = NORMAL 的全部流水，含开户 OPENING)
 * </pre>
 * <p>同时列出被隔离的异常流水与已生效的补偿事件，用于解释差异是否已经闭环：</p>
 * <ul>
 *   <li>仅有 EXCEPTIONAL 流水而无对应补偿 -> difference == 该异常流水金额，不守恒；</li>
 *   <li>异常流水补偿完成 -> 余额被单边修复，difference 归零，重新守恒；</li>
 *   <li>补偿只登记一次，重复对账结论稳定。</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LocalLedgerStore store;

    public ReconciliationService(LocalLedgerStore store) {
        this.store = store;
    }

    public ReconciliationReport reconcile(String tenantId) {
        List<ReconciliationReport.AccountLine> lines = store.accountsOf(tenantId).stream()
                .map(account -> buildLine(tenantId, account))
                .toList();
        ReconciliationReport report = new ReconciliationReport(tenantId, lines);
        log.info("reconcile tenant={} matched={} mismatches={} basis=balance_vs_normal_entry_sum",
                tenantId, report.isMatched(), report.getMismatches());
        return report;
    }

    private ReconciliationReport.AccountLine buildLine(String tenantId, TenantAccount account) {
        List<LedgerEntry> entries = store.entriesOf(tenantId, account.getAccountId());

        long normalSum = entries.stream()
                .filter(e -> e.getState() == EntryState.NORMAL)
                .mapToLong(LedgerEntry::getSignedDelta)
                .sum();
        long difference = account.getBalance() - normalSum;

        List<String> exceptionalIds = entries.stream()
                .filter(e -> e.getState() == EntryState.EXCEPTIONAL)
                .map(e -> e.getEntryId()
                        + (e.getCompensatedBy() != null ? "->compensated:" + e.getCompensatedBy()
                        : "->open"))
                .toList();

        List<String> compensatedEventRefs =
                store.compensationsOf(tenantId, account.getAccountId()).stream()
                        .map(CompensationRecord::eventRef)
                        .toList();

        return new ReconciliationReport.AccountLine(
                account.getAccountId(),
                account.getBalance(),
                normalSum,
                difference,
                difference == 0,
                exceptionalIds,
                compensatedEventRefs);
    }
}
