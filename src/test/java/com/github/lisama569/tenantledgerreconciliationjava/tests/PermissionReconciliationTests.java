package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Direction;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureReason;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限拒绝、对账不匹配与一次性补偿守恒。 */
public class PermissionReconciliationTests {

    private static final Logger log = LoggerFactory.getLogger(PermissionReconciliationTests.class);

    @TempDir
    Path tmp;

    @Test
    public void tenantOperatorCannotCompensate() {
        var h = TestSupport.fresh(tmp.resolve("p1.json"));
        h.ledger().openAccount("A1", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        LedgerEntry e = h.ledger().submitEntry("A1", "tenant-a", Direction.CREDIT, 20_00,
                "credit-1", TestSupport.operator("tenant-a"));
        h.recon().markAnomaly(e.getEntryId(), "mark-1", TestSupport.admin());

        ServiceException markDenied = assertThrows(ServiceException.class, () ->
                h.recon().markAnomaly(e.getEntryId(), "mark-bad", TestSupport.operator("tenant-a")));
        ServiceException compDenied = assertThrows(ServiceException.class, () ->
                h.recon().compensate(e.getEntryId(), "comp-bad", TestSupport.operator("tenant-a")));

        log.info("[权限拒绝] mark-bad -> {}; comp-bad -> {}；判定依据=仅 COMPENSATION_ADMIN 拥有对账补偿权限",
                markDenied.getReason(), compDenied.getReason());

        assertEquals(FailureReason.PERMISSION_DENIED, markDenied.getReason());
        assertEquals(FailureReason.PERMISSION_DENIED, compDenied.getReason());
        assertEquals(120_00, h.ledger().getAccount("A1", TestSupport.operator("tenant-a")).getBalance(),
                "被拒绝的补偿不得改变余额");
        long deniedAudits = h.repo().allOperations().stream()
                .filter(r -> r.getStatus() == OperationStatus.REJECTED
                        && r.getFailureReason() == FailureReason.PERMISSION_DENIED)
                .count();
        assertEquals(2, deniedAudits, "两次越权都应有 REJECTED 审计");
    }

    @Test
    public void reconcileMismatchDetectsUncompensatedAnomalyAndCompensationFixesIt() {
        var h = TestSupport.fresh(tmp.resolve("p2.json"));
        h.ledger().openAccount("A2", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        // 异常流水：一笔不该发生的扣款 DEBIT 3000，账面被错误减到 7000
        LedgerEntry bad = h.ledger().submitEntry("A2", "tenant-a", Direction.DEBIT, 30_00,
                "bad-debit", TestSupport.operator("tenant-a"));
        // 另一笔正常流水 CREDIT 500
        LedgerEntry normal = h.ledger().submitEntry("A2", "tenant-a", Direction.CREDIT, 5_00,
                "normal-credit", TestSupport.operator("tenant-a"));

        // 补偿前对账匹配（异常尚未被识别，账面就是流水结果）
        assertTrue(h.recon().reconcileAll().get(0).matched());

        // 管理员调查后将异常流水标记：此时账面 7500，但“干净流水重算”为 10500 → 对账不匹配
        h.recon().markAnomaly(bad.getEntryId(), "mark-1", TestSupport.admin());
        ReconciliationService.AccountCheckResult before = h.recon().reconcileAll().get(0);
        log.info("[对账不匹配] 账面={} 干净流水重算={} 匹配={} 待补偿异常={} 判定依据=已标记但未补偿的异常造成账面漂移",
                before.storedBalance(), before.recomputedBalance(), before.matched(),
                before.anomalyEntryIds());
        assertFalse_matched(before);
        assertEquals(75_00, before.storedBalance());
        assertEquals(105_00, before.recomputedBalance());
        assertEquals(List.of(bad.getEntryId()), before.anomalyEntryIds());

        // 未标记异常的流水不能直接补偿
        ServiceException notAnomaly = assertThrows(ServiceException.class, () ->
                h.recon().compensate(normal.getEntryId(), "comp-x", TestSupport.admin()));
        assertEquals(FailureReason.ENTRY_NOT_ANOMALY, notAnomaly.getReason());

        // 正式补偿：对 DEBIT 异常做反向 CREDIT 冲销，把钱加回去
        LedgerEntry comp = h.recon().compensate(bad.getEntryId(), "comp-1", TestSupport.admin());
        assertNotNull(comp);
        assertTrue(comp.isCompensation());
        assertEquals(Direction.CREDIT, comp.getDirection());
        assertEquals(30_00, comp.getAmount());
        assertEquals(bad.getEntryId(), comp.getReferenceId());

        long balance = h.ledger().getAccount("A2", TestSupport.operator("tenant-a")).getBalance();
        log.info("[补偿守恒] requestId=comp-1 异常={} 补偿流水={} 补偿后余额={}（10000 - 3000异常 + 500正常 + 3000冲销 = 10500）",
                bad.getEntryId(), comp.getEntryId(), balance);
        assertTrue(bad.isCompensated());
        assertEquals(105_00, balance);

        ReconciliationService.AccountCheckResult after = h.recon().reconcileAll().get(0);
        log.info("[补偿后对账] 账面={} 重算={} 匹配={} 待补偿异常={}",
                after.storedBalance(), after.recomputedBalance(), after.matched(), after.anomalyEntryIds());
        assertTrue(after.matched(), "补偿后账面余额必须与流水重算一致");
        assertTrue(after.anomalyEntryIds().isEmpty());

        // 守恒：重复对账结论不变
        ReconciliationService.AccountCheckResult again = h.recon().reconcileAll().get(0);
        assertEquals(after.storedBalance(), again.storedBalance());
        assertEquals(after.recomputedBalance(), again.recomputedBalance());
        assertTrue(again.matched());
    }

    @Test
    public void duplicateCompensationRequestDoesNotDoubleApply() {
        var h = TestSupport.fresh(tmp.resolve("p3.json"));
        h.ledger().openAccount("A3", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        LedgerEntry bad = h.ledger().submitEntry("A3", "tenant-a", Direction.CREDIT, 40_00,
                "bad", TestSupport.operator("tenant-a"));
        h.recon().markAnomaly(bad.getEntryId(), "mark", TestSupport.admin());

        LedgerEntry first = h.recon().compensate(bad.getEntryId(), "comp-dup", TestSupport.admin());
        LedgerEntry second = h.recon().compensate(bad.getEntryId(), "comp-dup", TestSupport.admin());
        ServiceException already = assertThrows(ServiceException.class, () ->
                h.recon().compensate(bad.getEntryId(), "comp-other", TestSupport.admin()));

        log.info("[补偿幂等] requestId=comp-dup 首次={} 重试同对象={} 换新requestId结果={}",
                first.getEntryId(), second.getEntryId(), already.getReason());

        assertSame(first, second, "同一补偿 requestId 必须返回同一流水");
        assertEquals(FailureReason.ENTRY_ALREADY_COMPENSATED, already.getReason());
        assertEquals(100_00, h.ledger().getAccount("A3", TestSupport.operator("tenant-a")).getBalance(),
                "补偿只能生效一次：异常 CREDIT 4000 被反向 DEBIT 4000 冲销，余额回到 10000");
        long compEntries = h.repo().allEntries().stream()
                .filter(LedgerEntry::isCompensation)
                .count();
        assertEquals(1, compEntries, "只能存在一条补偿流水");
    }

    private static void assertFalse_matched(ReconciliationService.AccountCheckResult r) {
        assertTrue(!r.matched(), "已标记未补偿异常必须造成对账不匹配");
    }
}
