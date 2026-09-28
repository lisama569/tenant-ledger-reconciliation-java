package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Direction;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureReason;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayFlag;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayReport;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Role;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import com.github.lisama569.tenantledgerreconciliationjava.service.HashChains;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务重启重放：最终余额不变；重复/乱序安全处理；缺号/篡改明确识别并只读保护。 */
public class RestartReplayTests {

    private static final Logger log = LoggerFactory.getLogger(RestartReplayTests.class);

    @TempDir
    Path tmp;

    @Test
    public void replayAfterRestartKeepsFinalBalance() {
        Path file = tmp.resolve("r1.json");
        var h1 = TestSupport.fresh(file);
        h1.ledger().openAccount("A1", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        h1.ledger().submitEntry("A1", "tenant-a", Direction.DEBIT, 30_00, "e1",
                TestSupport.operator("tenant-a"));
        h1.ledger().submitEntry("A1", "tenant-a", Direction.CREDIT, 12_00, "e2",
                TestSupport.operator("tenant-a"));
        long before = 82_00;

        var h2 = h1.restart();
        long after = h2.ledger().getAccount("A1", TestSupport.operator("tenant-a")).getBalance();
        ReplayReport report = h2.replay().lastReport();
        log.info("[重启重放] requestIds=open,e1,e2 重放前余额={} 重放后余额={} healthy={} 总记录={} 生效={}",
                before, after, report.healthy(), report.totalRecords(), report.applied());

        assertTrue(report.healthy());
        assertEquals(before, after, "重放历史记录不得改变最终余额");
        assertEquals(2, h2.repo().entriesByAccount("A1").size(), "流水由日志确定性重建");
    }

    @Test
    public void replaySkipsDuplicatesAndToleratesOutOfOrder() {
        Path file = tmp.resolve("r2.json");
        var h1 = TestSupport.fresh(file);
        h1.ledger().openAccount("A2", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        h1.ledger().submitEntry("A2", "tenant-a", Direction.DEBIT, 10_00, "dup",
                TestSupport.operator("tenant-a"));

        // 基于现有链尾，合法地追加两条记录 rA(seq=3)/rB(seq=4)，构造时保证哈希链正确
        List<OperationRecord> ops = new ArrayList<>(h1.repo().rawSnapshot().getOperations());
        OperationRecord rA = rawEntry(3, "seq-90", "A2", "tenant-a", Direction.CREDIT, 5_00);
        OperationRecord rB = rawEntry(4, "seq-91", "A2", "tenant-a", Direction.DEBIT, 2_00);
        chain(ops, rA, rB);

        // 物理存储上故意颠倒 rA/rB 顺序（乱序），并在尾部追加一条与 seq=2 完全相同的“重复 dup”记录
        OperationRecord duplicateOfSeq2 = copyShallow(ops.get(1));
        duplicateOfSeq2.setReplayFlag(ReplayFlag.NONE);
        ops.add(rB);
        ops.add(rA);
        ops.add(duplicateOfSeq2);

        LedgerState injected = new LedgerState();
        injected.setOperations(ops);
        injected.setLastSeq(4);
        injected.setLastHash(rB.getHash());
        h1.repo().persistRaw(injected);

        var h2 = h1.restart();
        ReplayReport report = h2.replay().lastReport();
        long balance = h2.ledger().getAccount("A2", TestSupport.operator("tenant-a")).getBalance();

        log.info("[重复+乱序] 最终余额={} 报告: 生效={} 跳过重复={} 乱序={} healthy={} 依据={}",
                balance, report.applied(), report.skippedDuplicates(), report.outOfOrder(),
                report.healthy(), report.notes());

        assertTrue(report.healthy(), "重复与乱序应被安全处理，账本仍健康");
        assertEquals(1, report.skippedDuplicates(), "重复的 dup 记录必须被跳过");
        assertEquals(1, report.outOfOrder(), "乱序应被识别");
        // 10000 - 1000(dup,仅一次) + 500(seq-90) - 200(seq-91) = 9300
        assertEquals(93_00, balance, "重复记录不得二次扣款，乱序重放结果不变");
    }

    @Test
    public void replayDetectsGapAndTamperAndLocksLedger() {
        // --- 场景 A：缺号中断（删除 seq=2，物理序列变成 1 -> 3）---
        Path gapFile = tmp.resolve("r3a.json");
        var g1 = TestSupport.fresh(gapFile);
        g1.ledger().openAccount("AG", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        g1.ledger().submitEntry("AG", "tenant-a", Direction.DEBIT, 10_00, "e1",
                TestSupport.operator("tenant-a"));
        g1.ledger().submitEntry("AG", "tenant-a", Direction.CREDIT, 5_00, "e2",
                TestSupport.operator("tenant-a"));
        LedgerState raw = g1.repo().rawSnapshot();
        List<OperationRecord> ops = new ArrayList<>(raw.getOperations());
        ops.remove(1);
        LedgerState gapped = new LedgerState();
        gapped.setOperations(ops);
        g1.repo().persistRaw(gapped);

        var g2 = g1.restart();
        ReplayReport gapReport = g2.replay().lastReport();
        log.info("[缺号中断] gapDetected={} healthy={} firstGapAfterSeq={}",
                gapReport.gapDetected(), gapReport.healthy(), gapReport.firstGapAfterSeq());
        assertFalse(gapReport.healthy());
        assertTrue(gapReport.gapDetected());
        assertEquals(1L, gapReport.firstGapAfterSeq());

        ServiceException locked = assertThrows(ServiceException.class, () ->
                g2.ledger().submitEntry("AG", "tenant-a", Direction.CREDIT, 1_00, "after-gap",
                        TestSupport.operator("tenant-a")));
        log.info("[缺号后写入] 结果=REJECTED({}) message={}", locked.getReason(), locked.getMessage());
        assertEquals(FailureReason.LEDGER_UNHEALTHY, locked.getReason(), "不安全状态必须拒绝新变更");

        // --- 场景 B：篡改金额但不重算 hash，哈希链失败 ---
        Path tamperFile = tmp.resolve("r3b.json");
        var t1 = TestSupport.fresh(tamperFile);
        t1.ledger().openAccount("AT", "tenant-a", 100_00, "open", TestSupport.operator("tenant-a"));
        t1.ledger().submitEntry("AT", "tenant-a", Direction.DEBIT, 10_00, "e1",
                TestSupport.operator("tenant-a"));
        LedgerState traw = t1.repo().rawSnapshot();
        traw.getOperations().get(1).getPayload().setAmount(99_00);
        t1.repo().persistRaw(traw);

        var t2 = t1.restart();
        ReplayReport tamperReport = t2.replay().lastReport();
        log.info("[哈希篡改] tampered={} healthy={} tamperedSeq={}",
                tamperReport.tampered(), tamperReport.healthy(), tamperReport.tamperedSeq());
        assertFalse(tamperReport.healthy());
        assertTrue(tamperReport.tampered());
        assertEquals(2L, tamperReport.tamperedSeq());
    }

    // ---- raw-log builders ----

    private OperationRecord rawEntry(long seq, String requestId, String accountId, String tenantId,
                                     Direction direction, long amount) {
        OperationPayload p = new OperationPayload();
        p.setAccountId(accountId);
        p.setTenantId(tenantId);
        p.setDirection(direction);
        p.setAmount(amount);
        OperationRecord r = new OperationRecord();
        r.setSeq(seq);
        r.setRequestId(requestId);
        r.setType(OperationType.ENTRY);
        r.setStatus(OperationStatus.APPLIED);
        r.setRequestedRole(Role.TENANT_OPERATOR);
        r.setPayload(p);
        r.setReplayFlag(ReplayFlag.NONE);
        return r;
    }

    /** 把新记录依次接到现有链尾，正确设置 prevHash/hash。 */
    private void chain(List<OperationRecord> base, OperationRecord... additions) {
        String prev = base.get(base.size() - 1).getHash();
        for (OperationRecord r : additions) {
            r.setPrevHash(prev);
            r.setHash(HashChains.hash(r, prev));
            prev = r.getHash();
        }
    }

    private OperationRecord copyShallow(OperationRecord src) {
        OperationRecord r = new OperationRecord();
        r.setSeq(src.getSeq());
        r.setRequestId(src.getRequestId());
        r.setType(src.getType());
        r.setStatus(src.getStatus());
        r.setFailureReason(src.getFailureReason());
        r.setRequestedRole(src.getRequestedRole());
        r.setPayload(src.getPayload());
        r.setPrevHash(src.getPrevHash());
        r.setHash(src.getHash());
        return r;
    }
}
