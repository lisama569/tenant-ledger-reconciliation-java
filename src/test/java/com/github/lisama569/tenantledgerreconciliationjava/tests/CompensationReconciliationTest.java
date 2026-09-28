package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryState;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureCode;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReconciliationReport;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReconciliationService;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常流水对账与补偿场景：
 * <ol>
 *   <li>异常隔离后对账出现明确差异；补偿后恢复守恒；</li>
 *   <li>补偿受 ADMIN 权限约束、同一事件只能生效一次、重复处理不改变结论；</li>
 *   <li>单边余额漂移（故障注入）可被对账识别并补偿修复，重启后结论稳定；</li>
 *   <li>AUDITOR 可标记异常但不能补偿；USER 两者都不能。</li>
 * </ol>
 */
class CompensationReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(CompensationReconciliationTest.class);

    @TempDir
    Path tempDir;

    private ReconciliationReport assertLine(ReconciliationService recon, String tenant,
                                            String accountId, long expectedBalance,
                                            boolean expectMatched) {
        ReconciliationReport report = recon.reconcile(tenant);
        ReconciliationReport.AccountLine line = report.getAccounts().stream()
                .filter(a -> a.accountId().equals(accountId)).findFirst().orElseThrow();
        log.info("[对账] tenant={} account={} balance={} normalEntrySum={} difference={} "
                        + "matched={} exceptional={} compensations={}",
                tenant, accountId, line.balance(), line.normalEntrySum(), line.difference(),
                line.matched(), line.exceptionalEntryIds(), line.compensatedEventRefs());
        assertEquals(expectedBalance, line.balance());
        assertEquals(expectMatched, line.matched());
        return report;
    }

    @Test
    void exceptionIsolationThenCompensationRestoresConservation() throws Exception {
        Path dir = tempDir.resolve("comp-case");
        java.nio.file.Files.createDirectories(dir);
        LocalLedgerStore store = new LocalLedgerStore(dir, new ObjectMapper());
        ObjectMapper mapper = new ObjectMapper();
        LedgerService service = new LedgerService(store, mapper);
        ReconciliationService recon = new ReconciliationService(store);
        String tenant = "t1";

        service.createAccount(LedgerTestSupport.ctx(tenant, "USER", "r1", "k-open"), "a1", 1000L);
        // 一笔错误扣款 400：余额 600。
        service.postEntry(LedgerTestSupport.ctx(tenant, "USER", "r2", "k-e1"),
                "a1", "e1", EntryType.DEBIT, 400L);
        service.postEntry(LedgerTestSupport.ctx(tenant, "USER", "r3", "k-e2"),
                "a1", "e2", EntryType.CREDIT, 50L);
        assertLine(recon, tenant, "a1", 650L, true);

        // AUDITOR 把错误扣款标记为异常（隔离流水，不动余额）：余额仍 650，
        // 正常流水和变为 1050，差异 -400，明确不守恒。
        OperationRecord mark = service.markException(
                LedgerTestSupport.ctx(tenant, "AUDITOR", "r4", "k-mark"),
                "a1", "e1", "EVT-1");
        assertEquals(OperationStatus.APPLIED, mark.getStatus());
        ReconciliationReport mismatchReport = assertLine(recon, tenant, "a1", 650L, false);
        assertEquals(1, mismatchReport.getMismatches().size());
        assertTrue(store.findEntry(tenant, "e1").orElseThrow().getState() == EntryState.EXCEPTIONAL);
        log.info("[对账不匹配] requestId=r4 判定依据=异常流水e1(-400)被隔离, 余额650 vs 正常流水和1050");

        // USER 尝试补偿 -> 权限拒绝。
        OperationRecord userComp = service.compensate(
                LedgerTestSupport.ctx(tenant, "USER", "r5", "k-comp-user"),
                "a1", "e1", "EVT-1", 400L);
        assertEquals(OperationStatus.REJECTED, userComp.getStatus());
        assertEquals(FailureCode.PERMISSION_DENIED, userComp.getFailureCode());
        log.info("[权限拒绝] requestId=r5 role=USER op=COMPENSATE code={} 判定依据=仅ADMIN可补偿",
                userComp.getFailureCode());

        // 错误金额的补偿（不是 -signedDelta）必须被拒绝。
        OperationRecord badAmount = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r6", "k-comp-bad"),
                "a1", "e1", "EVT-1", 999L);
        assertEquals(FailureCode.INVALID_REQUEST, badAmount.getFailureCode());

        // ADMIN 正确补偿 +400（单边余额修复，不产生业务流水）。
        OperationRecord comp = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r7", "k-comp"),
                "a1", "e1", "EVT-1", 400L);
        assertEquals(OperationStatus.APPLIED, comp.getStatus());
        assertLine(recon, tenant, "a1", 1050L, true);
        assertEquals("EVT-1", store.findEntry(tenant, "e1").orElseThrow().getCompensatedBy());
        log.info("[补偿生效] requestId=r7 event=EVT-1 delta=+400 最终余额=1050 matched=true "
                + "判定依据=单边余额修复+异常事件闭环");

        // 同一事件重复补偿：换幂等键也必须被拒绝（补偿只能生效一次）。
        OperationRecord dup = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r8", "k-comp-dup"),
                "a1", "e1", "EVT-1", 400L);
        assertEquals(FailureCode.COMPENSATION_ALREADY_APPLIED, dup.getFailureCode());
        // 用同一幂等键：返回首次结论，不二次生效。
        OperationRecord dupSameKey = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r9", "k-comp"),
                "a1", "e1", "EVT-1", 400L);
        assertEquals(comp.getSeq(), dupSameKey.getSeq());
        assertLine(recon, tenant, "a1", 1050L, true);
        log.info("[补偿幂等] requestId=r8/r9 event=EVT-1 code={}/replaySeq={} 最终余额仍=1050 "
                        + "判定依据=事件级一次补偿+幂等键",
                dup.getFailureCode(), dupSameKey.getSeq());

        // 重启重放后：异常状态、补偿闭环与余额全部保持。
        store.close();
        LocalLedgerStore restarted = new LocalLedgerStore(dir, mapper);
        ReplayAssertions(restarted, mapper, tenant);
    }

    private void ReplayAssertions(LocalLedgerStore restarted, ObjectMapper mapper, String tenant) {
        var report = restarted.replay();
        ReconciliationService recon2 = new ReconciliationService(restarted);
        ReconciliationReport after = recon2.reconcile(tenant);
        assertTrue(after.isMatched());
        assertEquals(1050L, restarted.findAccount(tenant, "a1").orElseThrow().getBalance());
        assertTrue(restarted.isCompensated("EVT-1"));
        log.info("[补偿后重启] applied={} matched=true 余额=1050 事件EVT-1仍为已补偿 "
                + "判定依据=重放MARK_EXCEPTION+COMPENSATE", report.getApplied());
    }

    @Test
    void auditorCanMarkButNotCompensateAndDoubleMarkIsRejected() {
        LocalLedgerStore store = LedgerTestSupport.newStore(tempDir, "perm");
        LedgerService service = new LedgerService(store, new ObjectMapper());
        service.createAccount(LedgerTestSupport.ctx("t1", "USER", "r1", "k1"), "a1", 100L);
        service.postEntry(LedgerTestSupport.ctx("t1", "USER", "r2", "k2"),
                "a1", "e1", EntryType.DEBIT, 10L);

        // USER 不能标记异常
        OperationRecord userMark = service.markException(
                LedgerTestSupport.ctx("t1", "USER", "r3", "k3"), "a1", "e1", "EVT-X");
        assertEquals(FailureCode.PERMISSION_DENIED, userMark.getFailureCode());

        // AUDITOR 可以标记
        OperationRecord auditorMark = service.markException(
                LedgerTestSupport.ctx("t1", "AUDITOR", "r4", "k4"), "a1", "e1", "EVT-X");
        assertEquals(OperationStatus.APPLIED, auditorMark.getStatus());

        // 重复标记被明确拒绝
        OperationRecord secondMark = service.markException(
                LedgerTestSupport.ctx("t1", "AUDITOR", "r5", "k5"), "a1", "e1", "EVT-Y");
        assertEquals(FailureCode.ENTRY_ALREADY_EXCEPTIONAL, secondMark.getFailureCode());

        // AUDITOR 不能补偿
        OperationRecord auditorComp = service.compensate(
                LedgerTestSupport.ctx("t1", "AUDITOR", "r6", "k6"),
                "a1", "e1", "EVT-X", 10L);
        assertEquals(FailureCode.PERMISSION_DENIED, auditorComp.getFailureCode());
        log.info("[权限矩阵] requestId=r3 USER->MARK={} r5 重复标记={} r6 AUDITOR->COMPENSATE={} "
                        + "判定依据=角色权限边界",
                userMark.getFailureCode(), secondMark.getFailureCode(),
                auditorComp.getFailureCode());
    }

    @Test
    void balanceDriftIsDetectedAndFixedByOneSidedCompensation() {
        LocalLedgerStore store = LedgerTestSupport.newStore(tempDir, "drift");
        LedgerService service = new LedgerService(store, new ObjectMapper());
        ReconciliationService recon = new ReconciliationService(store);
        String tenant = "t1";
        service.createAccount(LedgerTestSupport.ctx(tenant, "USER", "r1", "k1"), "a1", 1000L);
        service.postEntry(LedgerTestSupport.ctx(tenant, "USER", "r2", "k2"),
                "a1", "e1", EntryType.DEBIT, 200L);
        assertEquals(800L, store.findAccount(tenant, "a1").orElseThrow().getBalance());

        // 故障注入：绕过流水把余额改坏（模拟历史脏数据/外部系统漂移）。
        store.findAccount(tenant, "a1").orElseThrow().resetBalanceForReplay(700L);
        ReconciliationReport drifted = recon.reconcile(tenant);
        assertFalse(drifted.isMatched());
        assertEquals(-100L, drifted.getAccounts().get(0).difference());
        log.info("[余额漂移] balance=700 normalEntrySum=800 difference=-100 "
                + "判定依据=余额与正常流水和不一致");

        // ADMIN 单边补偿修复（不关联流水、不新增业务流水）。
        OperationRecord fixed = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r3", "k3"),
                "a1", null, "DRIFT-1", 100L);
        assertEquals(OperationStatus.APPLIED, fixed.getStatus());
        assertTrue(recon.reconcile(tenant).isMatched());

        // 重复修复（即使金额相同）也必须被拒绝，结论稳定。
        OperationRecord again = service.compensate(
                LedgerTestSupport.ctx(tenant, "ADMIN", "r4", "k4"),
                "a1", null, "DRIFT-1", 100L);
        assertEquals(FailureCode.COMPENSATION_ALREADY_APPLIED, again.getFailureCode());
        assertEquals(800L, store.findAccount(tenant, "a1").orElseThrow().getBalance());
        log.info("[漂移补偿] requestId=r3 DRIFT-1 delta=+100 matched=true r4重复拒绝code={} "
                + "最终余额=800 判定依据=事件级一次补偿", again.getFailureCode());
    }
}
