package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Direction;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureReason;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发与重复提交：同一 requestId 只入账一次；失败原因可区分。 */
public class ConcurrencyIdempotencyTests {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIdempotencyTests.class);

    @TempDir
    Path tmp;

    @Test
    public void concurrentSameRequestIdAppliedOnce() throws Exception {
        var h = TestSupport.fresh(tmp.resolve("c1.json"));
        h.ledger().openAccount("A1", "tenant-a", 100_00, "open-1",
                TestSupport.operator("tenant-a"));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicateRejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    h.ledger().submitEntry("A1", "tenant-a", Direction.DEBIT, 10_00,
                            "req-concurrent-1", TestSupport.operator("tenant-a"));
                    success.incrementAndGet();
                } catch (ServiceException e) {
                    if (e.getReason() == FailureReason.DUPLICATE_REQUEST) {
                        duplicateRejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[并发同一requestId] requestId=req-concurrent-1 线程数={} 成功={} 幂等拒绝={} 判定依据=写锁内 requestId 唯一",
                threads, success.get(), duplicateRejected.get());

        assertEquals(1, success.get(), "同一业务只能成功入账一次");
        assertEquals(threads - 1, duplicateRejected.get(), "其余并发请求必须以 DUPLICATE_REQUEST 拒绝");
        assertEquals(90_00, h.ledger().getAccount("A1", TestSupport.operator("tenant-a")).getBalance(),
                "余额只能变动一次: 10000-1000=9000");
        assertEquals(1, h.repo().entriesByAccount("A1").size(), "只允许产生一条流水");
    }

    @Test
    public void duplicateSequentialSubmissionReturnsSameEntry() {
        var h = TestSupport.fresh(tmp.resolve("c2.json"));
        h.ledger().openAccount("A2", "tenant-a", 50_00, "open-2",
                TestSupport.operator("tenant-a"));
        LedgerEntry first = h.ledger().submitEntry("A2", "tenant-a", Direction.CREDIT, 5_00,
                "req-seq-1", TestSupport.operator("tenant-a"));
        assertNotNull(first);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                h.ledger().submitEntry("A2", "tenant-a", Direction.CREDIT, 5_00,
                        "req-seq-1", TestSupport.operator("tenant-a")));
        log.info("[串行重复提交] requestId=req-seq-1 原流水={} 第二次结果=REJECTED({}) message={}",
                first.getEntryId(), ex.getReason(), ex.getMessage());

        assertEquals(FailureReason.DUPLICATE_REQUEST, ex.getReason());
        assertEquals(55_00, h.ledger().getAccount("A2", TestSupport.operator("tenant-a")).getBalance());
        assertEquals(1, h.repo().entriesByAccount("A2").size());
    }

    @Test
    public void insufficientFundsAndTenantMismatchRejectedWithDistinctReasons() {
        var h = TestSupport.fresh(tmp.resolve("c3.json"));
        h.ledger().openAccount("A3", "tenant-a", 3_00, "open-3",
                TestSupport.operator("tenant-a"));

        ServiceException funds = assertThrows(ServiceException.class, () ->
                h.ledger().submitEntry("A3", "tenant-a", Direction.DEBIT, 5_00,
                        "req-funds", TestSupport.operator("tenant-a")));
        ServiceException tenant = assertThrows(ServiceException.class, () ->
                h.ledger().submitEntry("A3", "tenant-b", Direction.DEBIT, 1_00,
                        "req-tenant", TestSupport.operator("tenant-b")));
        ServiceException unknown = assertThrows(ServiceException.class, () ->
                h.ledger().submitEntry("NOPE", "tenant-a", Direction.CREDIT, 1_00,
                        "req-unknown", TestSupport.operator("tenant-a")));
        ServiceException amount = assertThrows(ServiceException.class, () ->
                h.ledger().submitEntry("A3", "tenant-a", Direction.CREDIT, 0,
                        "req-amount", TestSupport.operator("tenant-a")));

        log.info("[失败原因区分] req-funds -> {}; req-tenant -> {}; req-unknown -> {}; req-amount -> {}",
                funds.getReason(), tenant.getReason(), unknown.getReason(), amount.getReason());

        assertEquals(FailureReason.INSUFFICIENT_FUNDS, funds.getReason());
        assertEquals(FailureReason.TENANT_MISMATCH, tenant.getReason());
        assertEquals(FailureReason.UNKNOWN_ACCOUNT, unknown.getReason());
        assertEquals(FailureReason.INVALID_AMOUNT, amount.getReason());
        assertEquals(3_00, h.ledger().getAccount("A3", TestSupport.operator("tenant-a")).getBalance(),
                "被拒绝的请求不得改变余额");
        assertEquals(0, h.repo().entriesByAccount("A3").size(), "被拒绝的请求不得产生流水");
    }
}
