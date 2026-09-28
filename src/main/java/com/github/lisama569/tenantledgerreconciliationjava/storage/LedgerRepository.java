package com.github.lisama569.tenantledgerreconciliationjava.storage;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Account;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayFlag;
import com.github.lisama569.tenantledgerreconciliationjava.service.HashChains;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 内存账本仓库：
 * - {@link #state} 中的操作日志是唯一持久化事实，每次变更同步原子落盘；
 * - 账户与流水是派生状态，仅在内存中，由 ReplayService 启动时从日志确定性重建；
 * - 所有写操作在同一把写锁内完成并落盘，保证单进程并发下串行化与原子性。
 */
public class LedgerRepository {

    private final LocalJsonStore store;
    private final LedgerState state;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    /** 重放健康位：检测到缺号/篡改等不可安全恢复的情况时置 false，账本进入只读保护。 */
    private volatile boolean healthy = true;
    private volatile String unhealthyReason;

    public LedgerRepository(Path storageFile) {
        this.store = new LocalJsonStore(storageFile);
        this.state = store.load();
    }

    public boolean isHealthy() {
        return healthy;
    }

    public String getUnhealthyReason() {
        return unhealthyReason;
    }

    /** 重放阶段设置健康位（须在写锁内或启动期调用）。 */
    public void setHealthy(boolean healthy, String reason) {
        this.healthy = healthy;
        this.unhealthyReason = healthy ? null : reason;
    }

    /** 在单一写锁内执行变更；无论业务成功还是业务异常（已留下拒绝审计记录）都同步落盘。 */
    public <T> T mutate(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            T result;
            try {
                result = action.get();
            } catch (RuntimeException e) {
                store.save(state);
                throw e;
            }
            store.save(state);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 只读访问（读锁）。 */
    public <T> T read(Supplier<T> query) {
        lock.readLock().lock();
        try {
            return query.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- 操作日志（须在写锁内调用）----

    public long nextSeq() {
        return state.getLastSeq() + 1;
    }

    public String lastHash() {
        String h = state.getLastHash();
        return h == null ? HashChains.GENESIS_HASH : h;
    }

    /** 追加记录：由仓库统一分配 seq 与哈希链，保证链路事实不可被业务层伪造。 */
    public OperationRecord appendOperation(OperationRecord record) {
        long seq = nextSeq();
        String prev = lastHash();
        record.setSeq(seq);
        record.setPrevHash(prev);
        record.setHash(HashChains.hash(record, prev));
        if (record.getReplayFlag() == null) {
            record.setReplayFlag(ReplayFlag.NONE);
        }
        state.getOperations().add(record);
        state.setLastSeq(seq);
        state.setLastHash(record.getHash());
        return record;
    }

    public Optional<OperationRecord> findAppliedByRequestId(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        for (OperationRecord r : state.getOperations()) {
            if (requestId.equals(r.getRequestId())
                    && r.getStatus() == com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus.APPLIED) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public List<OperationRecord> allOperations() {
        return new ArrayList<>(state.getOperations());
    }

    public int operationCount() {
        return state.getOperations().size();
    }

    // ---- 派生状态：账户（须在锁内调用）----

    public void putAccount(Account account) {
        accounts.put(account.getAccountId(), account);
    }

    public Optional<Account> findAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public List<Account> allAccounts() {
        return new ArrayList<>(accounts.values());
    }

    // ---- 派生状态：流水（须在锁内调用）----

    public void putEntry(LedgerEntry entry) {
        entries.put(entry.getEntryId(), entry);
    }

    public Optional<LedgerEntry> findEntry(String entryId) {
        return Optional.ofNullable(entries.get(entryId));
    }

    public List<LedgerEntry> entriesByAccount(String accountId) {
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry e : entries.values()) {
            if (accountId.equals(e.getAccountId())) {
                result.add(e);
            }
        }
        return result;
    }

    public List<LedgerEntry> allEntries() {
        return new ArrayList<>(entries.values());
    }

    // ---- 重放与测试钩子 ----

    /** 清空派生状态，准备从操作日志重建（须在写锁内调用）。 */
    public void resetDerivedState() {
        accounts.clear();
        entries.clear();
    }

    /**
     * 用磁盘读出的状态替换运行时操作日志（重放场景）。
     * 重放检测的就是磁盘事实；替换后 mutate 落盘会把重放标注一并持久化。
     */
    public void replaceRuntimeState(LedgerState raw) {
        state.setOperations(raw.getOperations());
        state.setLastSeq(raw.getLastSeq());
        state.setLastHash(raw.getLastHash());
    }

    /** 测试钩子：直接读取磁盘原始状态（模拟重启/损坏/乱序/缺号）。 */
    public LedgerState rawSnapshot() {
        return store.load();
    }

    /** 测试钩子：直接覆盖磁盘状态（不经内存、不经哈希链维护）。 */
    public void persistRaw(LedgerState raw) {
        store.save(raw);
    }
}
