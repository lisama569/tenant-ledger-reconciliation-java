package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationType;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import com.github.lisama569.tenantledgerreconciliationjava.storage.ReplayReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启重放场景：
 * <ul>
 *   <li>服务重启后重放历史记录，最终余额与流水完全一致；</li>
 *   <li>落盘后崩溃（内存未提交）通过重放恢复，结论不变；</li>
 *   <li>重复键、乱序、缺口、损坏/中断尾部必须被明确识别并安全处理。</li>
 * </ul>
 */
class LedgerReplayTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerReplayTest.class);

    @TempDir
    Path tempDir;

    @Test
    void replayAfterRestartProducesIdenticalState() throws Exception {
        Path dir = tempDir.resolve("restart");
        Files.createDirectories(dir);

        long balanceBefore;
        try (LocalLedgerStore store = new LocalLedgerStore(dir, new ObjectMapper())) {
            LedgerService service = new LedgerService(store, new ObjectMapper());
            service.createAccount(LedgerTestSupport.ctx("t1", "USER", "r1", "k1"), "a1", 1000L);
            service.postEntry(LedgerTestSupport.ctx("t1", "USER", "r2", "k2"),
                    "a1", "e1", EntryType.DEBIT, 200L);
            service.postEntry(LedgerTestSupport.ctx("t1", "USER", "r3", "k3"),
                    "a1", "e2", EntryType.CREDIT, 50L);
            // 一条被拒绝的记录也必须保留并不产生影响。
            OperationRecord rejected = service.postEntry(
                    LedgerTestSupport.ctx("t1", "USER", "r4", "k4"),
                    "a1", "e3", EntryType.DEBIT, 10_000L);
            assertEquals(OperationStatus.REJECTED, rejected.getStatus());
            balanceBefore = store.findAccount("t1", "a1").orElseThrow().getBalance();
        }

        // 模拟重启：丢弃内存态，仅靠 append-only 日志重建。
        LocalLedgerStore restarted = new LocalLedgerStore(dir, new ObjectMapper());
        ReplayReport report = restarted.replay();

        long balanceAfter = restarted.findAccount("t1", "a1").orElseThrow().getBalance();
        long normalSum = restarted.entriesOf("t1", "a1").stream()
                .mapToLong(e -> e.getSignedDelta()).sum();

        log.info("[重启重放] requestId=r1..r4 applied={} rejected={} 重放前余额={} 重放后余额={} "
                        + "正常流水和={} 异常={} 判定依据=APPLIED重放/REJECTED只登记",
                report.getApplied(), report.getRejected(), balanceBefore, balanceAfter,
                normalSum, report.hasAnomaly());

        assertEquals(balanceBefore, balanceAfter, "重放不得改变最终余额");
        assertEquals(850L, balanceAfter);
        assertEquals(balanceAfter, normalSum, "余额必须等于流水之和");
        assertEquals(3, report.getApplied());
        assertEquals(1, report.getRejected());
        assertFalse(report.hasAnomaly());
    }

    @Test
    void crashAfterLogAppendIsRecoveredByReplay() {
        Path dir = tempDir.resolve("crash");
        ObjectMapper mapper = new ObjectMapper();
        String tenant = "t1";

        LocalLedgerStore store = new LocalLedgerStore(dir, mapper);
        LedgerService service = new LedgerService(store, mapper);
        service.createAccount(LedgerTestSupport.ctx(tenant, "USER", "r1", "k1"), "a1", 1000L);

        // 在下一笔 APPLIED 日志落盘成功后立即模拟崩溃：内存态尚未提交。
        store.armCrashAfterNextAppend(rec -> {
            throw new LocalLedgerStore.SimulatedCrashException("simulated kill after append seq="
                    + rec.getSeq());
        });
        assertThrows(LocalLedgerStore.SimulatedCrashException.class, () -> service.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r2", "k2"),
                "a1", "e1", EntryType.DEBIT, 300L));

        // 崩溃实例的内存未提交（余额仍是 1000），不能继续信任该实例。
        assertEquals(1000L, store.findAccount(tenant, "a1").orElseThrow().getBalance());

        // 重启重放：已落盘的借方必须生效，且只能生效一次。
        LocalLedgerStore restarted = new LocalLedgerStore(dir, mapper);
        ReplayReport report = restarted.replay();
        long balance = restarted.findAccount(tenant, "a1").orElseThrow().getBalance();
        long debitCount = restarted.entriesOf(tenant, "a1").stream()
                .filter(e -> "e1".equals(e.getEntryId())).count();

        log.info("[崩溃恢复] requestId=r2 崩溃点=落盘后提交前 applied={} 重放后余额={} "
                        + "e1流水数={} 判定依据=日志先于内存态,重放补齐且不重复",
                report.getApplied(), balance, debitCount);

        assertEquals(700L, balance, "重放补齐已落盘的扣款");
        assertEquals(1, debitCount, "不得重复扣款");
        assertFalse(report.hasAnomaly());

        // 重启后用同一幂等键再提交：重放已登记首次结论，必须幂等返回，不二次扣款。
        LedgerService service2 = new LedgerService(restarted, mapper);
        OperationRecord replay = service2.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r3", "k2"),
                "a1", "e1", EntryType.DEBIT, 300L);
        assertEquals(OperationStatus.APPLIED, replay.getStatus());
        assertEquals(700L, restarted.findAccount(tenant, "a1").orElseThrow().getBalance());
        log.info("[崩溃后重复提交] requestId=r3 key=k2 首次seq={} 最终余额=700 判定依据=幂等重放",
                replay.getSeq());
    }

    @Test
    void duplicateOutOfOrderGapAndCorruptRecordsAreReportedSafely() throws Exception {
        Path dir = tempDir.resolve("anomaly");
        ObjectMapper mapper = new ObjectMapper();
        LocalLedgerStore store = new LocalLedgerStore(dir, mapper);

        // 1) 正常基线：seq=1 开户、seq=2 入账
        LedgerService service = new LedgerService(store, mapper);
        service.createAccount(LedgerTestSupport.ctx("t1", "USER", "r1", "k1"), "a1", 1000L);
        service.postEntry(LedgerTestSupport.ctx("t1", "USER", "r2", "k2"),
                "a1", "e1", EntryType.DEBIT, 100L);

        // 2) 重复键记录（seq=9）：安全跳过
        OperationRecord dup = newRecord(9, "t1", "r9", "k1", OperationType.CREATE_ACCOUNT,
                OperationStatus.APPLIED, payloadCreate("a1", 1000L, mapper), mapper);
        store.appendRecordPairForTest(dup);

        // 3) 乱序记录（seq=5，小于已见 9；相对 seq2 又是缺口 3..8）
        OperationRecord ooo = newRecord(5, "t1", "r5", "k5", OperationType.CREATE_ACCOUNT,
                OperationStatus.APPLIED, payloadCreate("aOOO", 0L, mapper), mapper);
        store.appendRecordPairForTest(ooo);

        // 4) 损坏记录：JSON 行 + 错误 CRC
        store.appendRawLineForTest("{\"seq\":7,\"type\":\"ENTRY\"}");
        store.appendRawLineForTest("1");

        // 5) 中断尾部：只有 JSON 行，缺 CRC 行
        store.appendRawLineForTest("{\"seq\":8,\"type\":\"ENTRY\"}");

        ReplayReport report = new LocalLedgerStore(dir, mapper).replay();
        LocalLedgerStore replayStore = new LocalLedgerStore(dir, mapper);
        report = replayStore.replay();
        log.info("[异常日志重放] applied={} rejected={} 警告={}",
                report.getApplied(), report.getRejected(), report.getWarnings());
        report.getWarnings().forEach(w -> log.info("[异常日志重放明细] {}", w));

        assertTrue(report.hasAnomaly());
        // seq=1,2 正常生效，最终余额 900；重复键与乱序账户均不得影响账务状态。
        assertEquals(900L, replayStore.findAccount("t1", "a1").orElseThrow().getBalance());
        assertTrue(replayStore.findAccount("t1", "aOOO").isEmpty(),
                "乱序记录必须安全跳过，不得静默创建账户");
        assertTrue(report.getDuplicateKeys().stream().anyMatch(s -> s.contains("k1")),
                "重复幂等键必须被识别");
        assertTrue(report.getOutOfOrderSeqs().stream().anyMatch(s -> s.contains("seq=5")),
                "乱序记录必须被识别");
        assertFalse(report.getGapWarnings().isEmpty(), "序号缺口必须明确告警");
        assertTrue(report.getCorruptLines().size() >= 2,
                "CRC 损坏行与中断尾部都必须登记");
        assertTrue(report.isTruncatedTail(), "中断尾部必须被识别并安全丢弃");
        assertEquals(2, report.getApplied(), "只有 seq=1,2 生效，无重复扣款/漏记");
    }

    private OperationRecord newRecord(long seq, String tenant, String requestId, String key,
                                      OperationType type, OperationStatus status,
                                      String payload, ObjectMapper mapper) {
        OperationRecord rec = new OperationRecord();
        rec.setSeq(seq);
        rec.setTenantId(tenant);
        rec.setRequestId(requestId);
        rec.setIdempotencyKey(key);
        rec.setRole("USER");
        rec.setType(type);
        rec.setStatus(status);
        rec.setPayload(payload);
        rec.setTimestamp(1_700_000_000_000L + seq);
        return rec;
    }

    private String payloadCreate(String accountId, long initial, ObjectMapper mapper) {
        OperationPayload p = new OperationPayload();
        p.setAccountId(accountId);
        p.setInitialBalance(initial);
        try {
            return mapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
