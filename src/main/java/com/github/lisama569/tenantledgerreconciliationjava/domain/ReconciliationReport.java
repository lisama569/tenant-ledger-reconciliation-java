package com.github.lisama569.tenantledgerreconciliationjava.domain;

import java.util.List;

/**
 * 对账报告。
 *
 * <p>守恒不变式（按租户、按账户）：</p>
 * <pre>
 *     account.balance == sum(entry.signedDelta for entry in NORMAL entries)
 * </pre>
 * EXCEPTIONAL 流水不计入右侧求和；COMPENSATION 流水（恒为 NORMAL）计入。
 * 报告还列出异常流水与已生效补偿，便于解释差异来源与补偿闭环。
 */
public final class ReconciliationReport {

    public record AccountLine(
            String accountId,
            long balance,
            long normalEntrySum,
            long difference,
            boolean matched,
            List<String> exceptionalEntryIds,
            List<String> compensatedEventRefs
    ) {
    }

    private final String tenantId;
    private final List<AccountLine> accounts;

    public ReconciliationReport(String tenantId, List<AccountLine> accounts) {
        this.tenantId = tenantId;
        this.accounts = List.copyOf(accounts);
    }

    public String getTenantId() {
        return tenantId;
    }

    public List<AccountLine> getAccounts() {
        return accounts;
    }

    public List<String> getMismatches() {
        return accounts.stream()
                .filter(a -> !a.matched())
                .map(a -> a.accountId() + ": balance=" + a.balance()
                        + " normalEntrySum=" + a.normalEntrySum()
                        + " difference=" + a.difference())
                .toList();
    }

    public boolean isMatched() {
        return accounts.stream().allMatch(AccountLine::matched);
    }
}
