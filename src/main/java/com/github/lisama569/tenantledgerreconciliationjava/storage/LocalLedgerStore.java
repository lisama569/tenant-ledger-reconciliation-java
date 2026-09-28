package com.github.lisama569.tenantledgerreconciliationjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.TenantAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 本地存储实现：
 * <ul>
 *   <li>内存态：账户表、流水表、幂等结果表，全部按 tenantId 隔离；</li>
 *   <li>持久态：append-only 操作日志（每行一条 {@link OperationRecord} JSON + CRC32 校验和）；</li>
 *   <li>启动重放：按 seq 顺序重建内存态，识别重复、乱序、缺口、损坏/中断尾部；</li>
 *   <li>并发：每把账户锁串行化同账户账务变更；幂等登记与日志落盘在锁/日志锁内完成，
 *       保证并发重复提交只有一笔真正入账。</li>
 * </ul>
 */
public class LocalLedgerStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalLedgerStore.class);

    private final Path logFile;
    private final ObjectMapper mapper;

    /** key = tenantId + "|" + accountId */
    private final Map<String, TenantAccount> accounts = new ConcurrentHashMap<>();
    /** key = tenantId + "|" + entryId */
    private final Map<String, LedgerEntry> entries = new ConcurrentHashMap<>();
    /** 每个账户下的 entryId 有序列表，用于流水求和。key 同账户键。 */
    private final Map<String, List<LedgerEntry>> accountEntries = new ConcurrentHashMap<>();
    /** 幂等结果：idempotencyKey -> 首次操作记录（含 REJECTED）。 */
    private final Map<String, OperationRecord> idempotency = new ConcurrentHashMap<>();
    /** 已生效补偿的事件号集合（补偿只能生效一次）。 */
    private final Map<String, CompensationRecord> compensatedEvents = new ConcurrentHashMap<>();
    /** 每账户锁。 */
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();

    private final Object logLock = new Object();
    private long nextSeq = 1;
    /** 最近一次重放报告（启动时生成），供查询与测试断言，不触发第二次重放。 */
    private volatile ReplayReport lastReplayReport;

    /** 测试钩子：在"日志落盘之后、内存态变更之前"抛异常，模拟崩溃/中断。 */
    private volatile Consumer<OperationRecord> crashAfterAppend;
    private volatile boolean crashArmed;

    public LocalLedgerStore(Path dataDir, ObjectMapper mapper) {
        this.mapper = mapper;
        try {
            Files.createDirectories(dataDir);
            this.logFile = dataDir.resolve("operations.log");
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot init ledger dir " + dataDir, e);
        }
    }

    // ------------------------------------------------------------------
    // 键与锁
    // ------------------------------------------------------------------

    public static String accountKey(String tenantId, String accountId) {
        return tenantId + "|" + accountId;
    }

    public static String entryKey(String tenantId, String entryId) {
        return tenantId + "|" + entryId;
    }

    /** 返回某账户的串行化锁，同一账户的账务变更必须持有该锁。 */
    public Object lockFor(String tenantId, String accountId) {
        return accountLocks.computeIfAbsent(accountKey(tenantId, accountId), k -> new Object());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Optional<TenantAccount> findAccount(String tenantId, String accountId) {
        return Optional.ofNullable(accounts.get(accountKey(tenantId, accountId)));
    }

    public Optional<LedgerEntry> findEntry(String tenantId, String entryId) {
        return Optional.ofNullable(entries.get(entryKey(tenantId, entryId)));
    }

    public List<LedgerEntry> entriesOf(String tenantId, String accountId) {
        return List.copyOf(accountEntries.getOrDefault(accountKey(tenantId, accountId), List.of()));
    }

    public List<TenantAccount> accountsOf(String tenantId) {
        return accounts.values().stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .toList();
    }

    public Optional<OperationRecord> findIdempotent(String idempotencyKey) {
        return Optional.ofNullable(idempotency.get(idempotencyKey));
    }

    public boolean isCompensated(String eventRef) {
        return compensatedEvents.containsKey(eventRef);
    }

    public CompensationRecord getCompensation(String eventRef) {
        return compensatedEvents.get(eventRef);
    }

    public List<CompensationRecord> compensationsOf(String tenantId, String accountId) {
        return compensatedEvents.values().stream()
                .filter(c -> c.tenantId().equals(tenantId) && c.accountId().equals(accountId))
                .map(c -> new CompensationRecord(c.tenantId(), c.accountId(), c.eventRef(),
                        c.delta(), c.idempotencyKey()))
                .toList();
    }

    /**
     * 跨租户探测账户：当本租户查不到 accountId 时，用于区分"不存在"与"属于别的租户"，
     * 从而给出 TENANT_MISMATCH 而非 ACCOUNT_NOT_FOUND。
     */
    public boolean accountExistsInOtherTenant(String tenantId, String accountId) {
        return accounts.values().stream()
                .anyMatch(a -> a.getAccountId().equals(accountId)
                        && !a.getTenantId().equals(tenantId));
    }

    /** 跨租户探测流水。 */
    public boolean entryExistsInOtherTenant(String tenantId, String entryId) {
        return entries.values().stream()
                .anyMatch(e -> e.getEntryId().equals(entryId)
                        && !e.getTenantId().equals(tenantId));
    }

    public long nextSeqForTest() {
        return nextSeq;
    }

    public ReplayReport getLastReplayReport() {
        return lastReplayReport;
    }

    // ------------------------------------------------------------------
    // 测试/故障注入支持（正常在线路径禁止使用）
    // ------------------------------------------------------------------

    /** 安装一次性崩溃钩子：下一条 APPLIED 日志落盘后立即抛出异常，模拟进程在内存提交前被杀。 */
    public void armCrashAfterNextAppend(Consumer<OperationRecord> hook) {
        this.crashAfterAppend = hook;
        this.crashArmed = true;
    }

    /** 清空全部内存态（不删日志），用于"重启"测试。 */
    public void resetMemoryState() {
        accounts.clear();
        entries.clear();
        accountEntries.clear();
        idempotency.clear();
        compensatedEvents.clear();
        accountLocks.clear();
        nextSeq = 1;
    }

    // ------------------------------------------------------------------
    // 内存态变更（仅允许在对应账户锁内、日志落盘后调用）
    // ------------------------------------------------------------------

    public TenantAccount putAccount(TenantAccount account) {
        accounts.put(accountKey(account.getTenantId(), account.getAccountId()), account);
        accountEntries.putIfAbsent(accountKey(account.getTenantId(), account.getAccountId()),
                new ArrayList<>());
        return account;
    }

    public void putEntry(LedgerEntry entry) {
        entries.put(entryKey(entry.getTenantId(), entry.getEntryId()), entry);
        accountEntries.computeIfAbsent(accountKey(entry.getTenantId(), entry.getAccountId()),
                k -> new ArrayList<>()).add(entry);
    }

    public void putIdempotent(String idempotencyKey, OperationRecord record) {
        idempotency.put(idempotencyKey, record);
    }

    /** 登记补偿生效事件。返回 true 表示首次登记；false 表示该事件已补偿过（不得二次生效）。 */
    public boolean registerCompensation(CompensationRecord record) {
        return compensatedEvents.putIfAbsent(record.eventRef(), record) == null;
    }

    // ------------------------------------------------------------------
    // 日志落盘
    // ------------------------------------------------------------------

    /**
     * 分配序号、计算校验和并以 append-only 方式落盘（两逻辑行：JSON / CRC32），
     * 随后强制刷盘。整个过程在日志锁内串行，保证序号连续与写完整性。
     *
     * <p>若安装了一次性崩溃钩子，会在落盘成功后、调用方提交内存态之前抛出
     * {@link SimulatedCrashException}，模拟"日志已持久化但内存未提交"的崩溃点。</p>
     */
    public OperationRecord appendRecord(OperationRecord record) {
        synchronized (logLock) {
            record.setSeq(nextSeq++);
            if (record.getTimestamp() <= 0) {
                record.setTimestamp(System.currentTimeMillis());
            }
            String json;
            try {
                json = mapper.writeValueAsString(record);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot serialize operation record", e);
            }
            long crc = crc32(json);
            record.setChecksum(crc);
            // 重新序列化以把 checksum 写入行内容（校验和字段自身不参与计算）。
            String finalJson;
            try {
                finalJson = mapper.writeValueAsString(record);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot serialize operation record", e);
            }
            String line = finalJson + System.lineSeparator() + crc + System.lineSeparator();
            try {
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append operation log", e);
            }
            log.info("operation-log appended seq={} type={} status={} key={} tenant={} request={}",
                    record.getSeq(), record.getType(), record.getStatus(),
                    record.getIdempotencyKey(), record.getTenantId(), record.getRequestId());
            if (crashArmed && crashAfterAppend != null
                    && record.getStatus() == OperationStatus.APPLIED) {
                crashArmed = false;
                Consumer<OperationRecord> hook = crashAfterAppend;
                hook.accept(record);
            }
            return record;
        }
    }

    /** 测试专用：向日志尾部直接追加一行原始内容，用于构造乱序/损坏/重复日志。 */
    public void appendRawLineForTest(String rawLine) {
        synchronized (logLock) {
            try {
                Files.writeString(logFile, rawLine + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("append raw line failed", e);
            }
        }
    }

    /**
     * 测试专用：以显式序号追加一条结构完整（JSON + 正确 CRC）的记录，
     * 不触碰内存态，用于构造重复幂等键、乱序、缺口日志。
     */
    public void appendRecordPairForTest(OperationRecord record) {
        synchronized (logLock) {
            try {
                record.setChecksum(null);
                String canonical = mapper.writeValueAsString(record);
                long crc = crc32(canonical);
                record.setChecksum(crc);
                String finalJson = mapper.writeValueAsString(record);
                Files.writeString(logFile,
                        finalJson + System.lineSeparator() + crc + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("append test record failed", e);
            }
        }
    }

    static long crc32(String s) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** 模拟崩溃：提示调用方放弃当前实例并基于日志重放恢复。 */
    public static final class SimulatedCrashException extends RuntimeException {
        public SimulatedCrashException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 启动重放
    // ------------------------------------------------------------------

    /**
     * 从操作日志重建内存态。
     *
     * <p>处理规则（任何异常都显式登记，绝不静默漏记/重复扣款/跨租户串账）：</p>
     * <ol>
     *   <li>每条逻辑记录为两行：JSON 行 + CRC32 行；单行缺失/为空 => 中断尾部，安全丢弃；</li>
     *   <li>JSON 无法解析或 CRC 不匹配 => 损坏行，跳过并告警；</li>
     *   <li>seq 必须严格连续递增：小/等于已见最大 seq 记为乱序（跳过），缺口明确告警；</li>
     *   <li>idempotencyKey 重复 => 安全跳过（首次结果已在内存），登记重复明细；</li>
     *   <li>APPLIED 记录重放其账务效果；REJECTED 记录只登记幂等结果，不产生任何账务影响。</li>
     * </ol>
     */
    public ReplayReport replay() {
        synchronized (logLock) {
            resetMemoryState();
            ReplayReport report = new ReplayReport();
            List<String> lines;
            try {
                lines = Files.exists(logFile)
                        ? Files.readAllLines(logFile, StandardCharsets.UTF_8)
                        : List.of();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read operation log", e);
            }

            long expectedSeq = 1;
            int lineNo = 0;
            int i = 0;
            while (i < lines.size()) {
                lineNo = i + 1;
                String jsonLine = lines.get(i);
                if (jsonLine == null || jsonLine.isBlank()) {
                    i++;
                    continue;
                }
                // 逻辑记录的第二行（CRC）缺失 => 写入中断，尾部截断安全丢弃。
                if (i + 1 >= lines.size()) {
                    report.setTruncatedTail(true);
                    report.getCorruptLines().add("line " + lineNo + " (missing checksum line)");
                    break;
                }
                String crcLine = lines.get(i + 1);
                String currentJson = jsonLine;
                int currentLineNo = lineNo;
                OperationRecord rec = parseAndVerify(currentJson, crcLine, currentLineNo, report);
                if (rec == null) {
                    // 损坏记录：尝试对齐到下一个"JSON+CRC"对（跳过这两行）。
                    i += 2;
                    continue;
                }

                long seq = rec.getSeq();
                if (seq < expectedSeq) {
                    report.getOutOfOrderSeqs().add("seq=" + seq + " at line " + currentLineNo);
                    i += 2;
                    continue;
                }
                if (seq > expectedSeq) {
                    report.getGapWarnings().add("sequence gap: expected " + expectedSeq
                            + " but found " + seq + " at line " + currentLineNo
                            + " (missing " + (seq - expectedSeq) + " record(s))");
                    expectedSeq = seq;
                }

                if (idempotency.containsKey(rec.getIdempotencyKey())) {
                    report.getDuplicateKeys().add(rec.getIdempotencyKey() + " (seq=" + seq + ")");
                    i += 2;
                    expectedSeq++;
                    continue;
                }

                applyRecordOnReplay(rec, report);
                idempotency.put(rec.getIdempotencyKey(), rec);
                expectedSeq++;
                i += 2;
            }

            nextSeq = expectedSeq;
            this.lastReplayReport = report;
            log.info("replay finished: applied={} rejected={} anomalies={}",
                    report.getApplied(), report.getRejected(), report.hasAnomaly());
            report.getWarnings().forEach(w -> log.warn("replay anomaly: {}", w));
            return report;
        }
    }

    private OperationRecord parseAndVerify(String jsonLine, String crcLine,
                                           int lineNo, ReplayReport report) {
        long crcExpected;
        try {
            crcExpected = Long.parseLong(crcLine.trim());
        } catch (NumberFormatException e) {
            report.getCorruptLines().add("line " + lineNo + " (bad checksum line)");
            return null;
        }
        OperationRecord rec;
        long crcActual;
        try {
            rec = mapper.readValue(jsonLine, OperationRecord.class);
            Long stored = rec.getChecksum();
            rec.setChecksum(null);
            String canonical = mapper.writeValueAsString(rec);
            crcActual = crc32(canonical);
            rec.setChecksum(stored);
        } catch (Exception e) {
            report.getCorruptLines().add("line " + lineNo + " (unparseable JSON: "
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (crcActual != crcExpected) {
            report.getCorruptLines().add("line " + lineNo + " seq=" + rec.getSeq()
                    + " (checksum mismatch: stored=" + crcExpected + " actual=" + crcActual + ")");
            return null;
        }
        return rec;
    }

    private void applyRecordOnReplay(OperationRecord rec, ReplayReport report) {
        if (rec.getStatus() == OperationStatus.REJECTED) {
            report.incRejected();
            return;
        }
        OperationPayload p = readPayload(rec);
        try {
            switch (rec.getType()) {
                case CREATE_ACCOUNT -> replayCreate(rec, p);
                case ENTRY -> replayEntry(rec, p);
                case MARK_EXCEPTION -> replayMarkException(rec, p);
                case COMPENSATE -> replayCompensate(rec, p);
            }
            report.incApplied();
        } catch (RuntimeException e) {
            // 重放理论上不应失败（日志是首次成功执行的事实）。失败意味着日志被外部破坏，
            // 显式登记为损坏记录，阻止"静默漏记"。
            report.getCorruptLines().add("seq=" + rec.getSeq()
                    + " (replay application failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")");
            throw e;
        }
    }

    private OperationPayload readPayload(OperationRecord rec) {
        try {
            return mapper.readValue(rec.getPayload(), OperationPayload.class);
        } catch (IOException e) {
            throw new UncheckedIOException("bad payload at seq=" + rec.getSeq(), e);
        }
    }

    private void replayCreate(OperationRecord rec, OperationPayload p) {
        long init = p.getInitialBalance() == null ? 0L : p.getInitialBalance();
        TenantAccount account = new TenantAccount(rec.getTenantId(), p.getAccountId(),
                0L, rec.getTimestamp());
        putAccount(account);
        if (init > 0) {
            // 开户初始余额以一笔系统贷方 OPENING 流水入账，使
            // "余额 == 正常流水之和" 的守恒式自开户起即成立。
            account.applySignedDelta(init);
            putEntry(new LedgerEntry(rec.getTenantId(), p.getAccountId(),
                    openingEntryId(p.getAccountId()), EntryType.CREDIT, init,
                    rec.getRequestId(), rec.getTimestamp()));
        }
    }

    private void replayEntry(OperationRecord rec, OperationPayload p) {
        long signedDelta = p.getEntryType() == EntryType.DEBIT
                ? -p.getAmount() : p.getAmount();
        TenantAccount account = accounts.get(accountKey(rec.getTenantId(), p.getAccountId()));
        account.applySignedDelta(signedDelta);
        LedgerEntry entry = new LedgerEntry(rec.getTenantId(), p.getAccountId(), p.getEntryId(),
                p.getEntryType(), signedDelta, rec.getRequestId(), rec.getTimestamp());
        putEntry(entry);
    }

    private void replayMarkException(OperationRecord rec, OperationPayload p) {
        LedgerEntry entry = entries.get(entryKey(rec.getTenantId(), p.getEntryId()));
        if (entry.getState() == com.github.lisama569.tenantledgerreconciliationjava.domain.EntryState.NORMAL) {
            entry.markExceptional(p.getEventRef());
        }
    }

    private void replayCompensate(OperationRecord rec, OperationPayload p) {
        TenantAccount account = accounts.get(accountKey(rec.getTenantId(), p.getAccountId()));
        // 补偿是"单边余额调整"：不产生业务流水，仅修复余额；业务流水的隔离由
        // MARK_EXCEPTION 完成。这样守恒式 balance == sum(NORMAL entry delta) 恒成立。
        account.applySignedDelta(p.getCompensationDelta());
        if (p.getEntryId() != null) {
            LedgerEntry entry = entries.get(entryKey(rec.getTenantId(), p.getEntryId()));
            if (entry != null && entry.getCompensatedBy() == null) {
                entry.closeWithCompensation(p.getEventRef());
            }
        }
        registerCompensation(new CompensationRecord(rec.getTenantId(), p.getAccountId(),
                p.getEventRef(), p.getCompensationDelta(), rec.getIdempotencyKey()));
    }

    /** 开户初始余额对应的系统流水号。 */
    public static String openingEntryId(String accountId) {
        return "OPENING:" + accountId;
    }

    @Override
    public void close() {
        // 内存态存储，无需释放外部句柄；实现 AutoCloseable 以便测试表达"实例生命周期结束"。
    }
}


