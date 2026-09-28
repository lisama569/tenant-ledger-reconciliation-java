package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureCode;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与重复提交场景：
 * <ul>
 *   <li>多线程用同一个 idempotencyKey 提交同一笔借方，只能入账一次；</li>
 *   <li>顺序重试同一 key 返回首次结论（含首次被拒绝的场景）；</li>
 *   <li>余额不足、租户不匹配、非法操作必须被拒绝且失败码可区分。</li>
 * </ul>
 */
class LedgerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerConcurrencyTest.class);

    @TempDir
    Path tempDir;

    @Test
    void concurrentDuplicateSubmissionPostsOnlyOnce() throws Exception {
        LocalLedgerStore store = LedgerTestSupport.newStore(tempDir, "conc");
        LedgerService service = new LedgerService(store, new ObjectMapper());
        String tenant = "tenant-A";

        OperationRecord open = service.createAccount(
                LedgerTestSupport.ctx(tenant, "USER", "req-open", "key-open"),
                "acct", 1000L);
        assertEquals(OperationStatus.APPLIED, open.getStatus());

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<OperationStatus> results = ConcurrentHashMap.newKeySet();
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            // 所有线程复用同一幂等键（模拟同一笔业务被重复/并发提交），但请求标识不同。
            String requestId = "req-debit-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    OperationContext ctx = LedgerTestSupport.ctx(tenant, "USER", requestId, "same-key");
                    OperationRecord rec = service.postEntry(ctx, "acct", "entry-dup",
                            EntryType.DEBIT, 700L);
                    synchronized (seqs) {
                        seqs.add(rec.getSeq());
                    }
                    results.add(rec.getStatus());
                } catch (Exception e) {
                    log.error("worker error requestId={}", requestId, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers should finish");
        pool.shutdown();

        long balance = store.findAccount(tenant, "acct").orElseThrow().getBalance();
        long entryCount = store.entriesOf(tenant, "acct").stream()
                .filter(e -> e.getEntryId().equals("entry-dup"))
                .count();
        long distinctSeqs = seqs.stream().distinct().count();

        log.info("[并发重复提交] requestId=req-debit-0..{} threads={} 结果集合={} 返回不同seq数={} "
                        + "实际entry-dup流水数={} 最终余额={} 判定依据=同一idempotencyKey只首次生效",
                threads - 1, threads, results, distinctSeqs, entryCount, balance);

        assertEquals(Set.of(OperationStatus.APPLIED), results, "所有响应都应是首次成功结论");
        assertEquals(1, entryCount, "同一笔业务不得重复入账");
        assertEquals(1, distinctSeqs, "所有重复响应必须指向同一条操作记录");
        assertEquals(300L, balance, "1000 - 700 = 300");
    }

    @Test
    void sequentialRetryReusesFirstOutcomeIncludingRejection() {
        LocalLedgerStore store = LedgerTestSupport.newStore(tempDir, "retry");
        LedgerService service = new LedgerService(store, new ObjectMapper());
        String tenant = "tenant-B";
        service.createAccount(LedgerTestSupport.ctx(tenant, "USER", "r1", "k-open"), "acct", 50L);

        // 首次借方失败：余额不足。
        OperationRecord first = service.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r2", "k-debit"),
                "acct", "e1", EntryType.DEBIT, 80L);
        assertEquals(OperationStatus.REJECTED, first.getStatus());
        assertEquals(FailureCode.INSUFFICIENT_FUNDS, first.getFailureCode());

        // 不同请求标识、同一幂等键重试：必须返回首次的 REJECTED 结论，不产生新记录。
        OperationRecord retry = service.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r3", "k-debit"),
                "acct", "e1", EntryType.DEBIT, 80L);
        log.info("[重复提交-失败幂等] requestId=r3 key=k-debit 首次requestId=r2 seq={} "
                        + "结果={} 失败码={} 判定依据=幂等重放返回首次结论",
                retry.getSeq(), retry.getStatus(), retry.getFailureCode());
        assertEquals(first.getSeq(), retry.getSeq());
        assertEquals(OperationStatus.REJECTED, retry.getStatus());
        assertEquals(FailureCode.INSUFFICIENT_FUNDS, retry.getFailureCode());
        assertEquals(50L, store.findAccount(tenant, "acct").orElseThrow().getBalance());

        // 成功请求后重复提交同样只生效一次。
        OperationRecord ok = service.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r4", "k-ok"),
                "acct", "e2", EntryType.CREDIT, 100L);
        OperationRecord okRetry = service.postEntry(
                LedgerTestSupport.ctx(tenant, "USER", "r5", "k-ok"),
                "acct", "e2", EntryType.CREDIT, 100L);
        assertEquals(ok.getSeq(), okRetry.getSeq());
        assertEquals(150L, store.findAccount(tenant, "acct").orElseThrow().getBalance());
        log.info("[重复提交-成功幂等] requestId=r5 key=k-ok seq={} 最终余额=150 判定依据=幂等重放",
                okRetry.getSeq());
    }

    @Test
    void rejectsWithDistinguishableFailureCodes() {
        LocalLedgerStore store = LedgerTestSupport.newStore(tempDir, "codes");
        LedgerService service = new LedgerService(store, new ObjectMapper());
        service.createAccount(LedgerTestSupport.ctx("t1", "USER", "r1", "k1"), "a1", 10L);
        service.createAccount(LedgerTestSupport.ctx("t2", "USER", "r2", "k2"), "aX", 10L);

        // 余额不足
        OperationRecord noFunds = service.postEntry(
                LedgerTestSupport.ctx("t1", "USER", "r3", "k3"),
                "a1", "e1", EntryType.DEBIT, 100L);
        // 账户属于别的租户
        OperationRecord crossTenant = service.postEntry(
                LedgerTestSupport.ctx("t1", "USER", "r4", "k4"),
                "aX", "e2", EntryType.CREDIT, 1L);
        // 无效操作：金额非法 / entryId 缺失
        OperationRecord badAmount = service.postEntry(
                LedgerTestSupport.ctx("t1", "USER", "r5", "k5"),
                "a1", "e3", EntryType.DEBIT, 0L);
        OperationRecord missingEntry = service.postEntry(
                LedgerTestSupport.ctx("t1", "USER", "r6", "k6"),
                "a1", "  ", EntryType.DEBIT, 1L);
        // 无权限：USER 角色不能标记异常
        OperationRecord noPerm = service.markException(
                LedgerTestSupport.ctx("t1", "USER", "r7", "k7"), "a1", "eX", "ev1");

        log.info("[失败原因] requestId=r3 code={} reason={}", noFunds.getFailureCode(),
                noFunds.getFailureReason());
        log.info("[失败原因] requestId=r4 code={} reason={}", crossTenant.getFailureCode(),
                crossTenant.getFailureReason());
        log.info("[失败原因] requestId=r5 code={} reason={}", badAmount.getFailureCode(),
                badAmount.getFailureReason());
        log.info("[失败原因] requestId=r6 code={} reason={}", missingEntry.getFailureCode(),
                missingEntry.getFailureReason());
        log.info("[失败原因] requestId=r7 code={} reason={}", noPerm.getFailureCode(),
                noPerm.getFailureReason());

        assertEquals(FailureCode.INSUFFICIENT_FUNDS, noFunds.getFailureCode());
        assertEquals(FailureCode.TENANT_MISMATCH, crossTenant.getFailureCode());
        assertEquals(FailureCode.INVALID_REQUEST, badAmount.getFailureCode());
        assertEquals(FailureCode.INVALID_REQUEST, missingEntry.getFailureCode());
        assertEquals(FailureCode.PERMISSION_DENIED, noPerm.getFailureCode());
        assertEquals(10L, store.findAccount("t1", "a1").orElseThrow().getBalance(),
                "所有拒绝均不得改动余额");
    }
}
