package com.github.lisama569.tenantledgerreconciliationjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryState;
import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureCode;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerException;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Roles;
import com.github.lisama569.tenantledgerreconciliationjava.domain.TenantAccount;
import com.github.lisama569.tenantledgerreconciliationjava.storage.CompensationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 账务命令服务。所有写操作走统一管道：
 *
 * <pre>
 *   全局串行化 → 幂等重放判定 → 权限校验 → 业务校验 → 操作日志先落盘 → 内存态变更 → 幂等登记
 * </pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>命令在单把操作锁内全串行执行，保证并发重复提交（同一 idempotencyKey）只有首次生效，
 *       后来者拿到首次结论，绝不重复扣款/入账；</li>
 *   <li>日志先于内存态落盘：即便进程在落盘后、提交内存前崩溃，重启重放也能得出同样的结论；</li>
 *   <li>被拒绝的命令同样写操作日志（REJECTED + 失败码），既参与幂等去重，也使失败可解释；</li>
 *   <li>租户隔离：所有查询都带 tenantId；账户/流水若存在于其他租户，返回 TENANT_MISMATCH。</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LocalLedgerStore store;
    private final ObjectMapper mapper;
    private final Object opLock = new Object();

    public LedgerService(LocalLedgerStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** 把"校验"与"生效"拆开的内部操作：校验只抛异常；生效在日志落盘后执行。 */
    private interface Mutator {
        void validate();

        void apply();
    }

    // ------------------------------------------------------------------
    // 对外命令
    // ------------------------------------------------------------------

    public OperationRecord createAccount(OperationContext ctx, String accountId,
                                         Long initialBalance) {
        OperationPayload payload = new OperationPayload();
        payload.setAccountId(accountId);
        payload.setInitialBalance(initialBalance);

        long init = initialBalance == null ? 0L : initialBalance;
        return execute(OperationType.CREATE_ACCOUNT, ctx, payload, new Mutator() {
            @Override
            public void validate() {
                requireText(accountId, "accountId");
                if (init < 0) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "initialBalance must not be negative: " + init);
                }
                if (store.findAccount(ctx.tenantId(), accountId).isPresent()) {
                    throw reject(FailureCode.ACCOUNT_ALREADY_EXISTS,
                            "account already exists in tenant: " + accountId);
                }
                if (store.accountExistsInOtherTenant(ctx.tenantId(), accountId)) {
                    throw reject(FailureCode.TENANT_MISMATCH,
                            "account " + accountId + " belongs to another tenant");
                }
            }

            @Override
            public void apply() {
                TenantAccount account = new TenantAccount(ctx.tenantId(), accountId, 0L,
                        System.currentTimeMillis());
                store.putAccount(account);
                if (init > 0) {
                    // 初始余额以系统贷方流水入账，保证 balance == sum(NORMAL entries) 恒成立。
                    account.applySignedDelta(init);
                    store.putEntry(new LedgerEntry(ctx.tenantId(), accountId,
                            LocalLedgerStore.openingEntryId(accountId), EntryType.CREDIT,
                            init, ctx.requestId(), System.currentTimeMillis()));
                }
            }
        });
    }

    public OperationRecord postEntry(OperationContext ctx, String accountId, String entryId,
                                     EntryType entryType, long amount) {
        OperationPayload payload = new OperationPayload();
        payload.setAccountId(accountId);
        payload.setEntryId(entryId);
        payload.setEntryType(entryType);
        payload.setAmount(amount);

        return execute(OperationType.ENTRY, ctx, payload, new Mutator() {
            @Override
            public void validate() {
                requireText(accountId, "accountId");
                requireText(entryId, "entryId");
                if (entryType == null || entryType == EntryType.COMPENSATION) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "entryType must be DEBIT or CREDIT");
                }
                if (amount <= 0) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "amount must be positive: " + amount);
                }
                TenantAccount account = mustGetAccount(ctx.tenantId(), accountId);
                if (store.findEntry(ctx.tenantId(), entryId).isPresent()) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "entryId already exists: " + entryId
                                    + " (business retries must reuse idempotencyKey)");
                }
                if (store.entryExistsInOtherTenant(ctx.tenantId(), entryId)) {
                    throw reject(FailureCode.TENANT_MISMATCH,
                            "entry " + entryId + " belongs to another tenant");
                }
                if (entryType == EntryType.DEBIT && account.getBalance() < amount) {
                    throw reject(FailureCode.INSUFFICIENT_FUNDS,
                            "debit " + amount + " exceeds balance " + account.getBalance()
                                    + " for account " + accountId);
                }
            }

            @Override
            public void apply() {
                TenantAccount account = store.findAccount(ctx.tenantId(), accountId).orElseThrow();
                long signedDelta = entryType == EntryType.DEBIT ? -amount : amount;
                account.applySignedDelta(signedDelta);
                store.putEntry(new LedgerEntry(ctx.tenantId(), accountId, entryId, entryType,
                        signedDelta, ctx.requestId(), System.currentTimeMillis()));
            }
        });
    }

    public OperationRecord markException(OperationContext ctx, String accountId, String entryId,
                                         String eventRef) {
        OperationPayload payload = new OperationPayload();
        payload.setAccountId(accountId);
        payload.setEntryId(entryId);
        payload.setEventRef(eventRef);

        return execute(OperationType.MARK_EXCEPTION, ctx, payload, new Mutator() {
            @Override
            public void validate() {
                requireText(accountId, "accountId");
                requireText(entryId, "entryId");
                requireText(eventRef, "eventRef");
                mustGetAccount(ctx.tenantId(), accountId);
                LedgerEntry entry = mustGetEntry(ctx.tenantId(), entryId);
                if (!entry.getAccountId().equals(accountId)) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "entry " + entryId + " does not belong to account " + accountId);
                }
                if (entry.getState() == EntryState.EXCEPTIONAL) {
                    throw reject(FailureCode.ENTRY_ALREADY_EXCEPTIONAL,
                            "entry " + entryId + " already exceptional under event "
                                    + entry.getExceptionRef());
                }
            }

            @Override
            public void apply() {
                store.findEntry(ctx.tenantId(), entryId).orElseThrow()
                        .markExceptional(eventRef);
            }
        });
    }

    /**
     * 补偿。两类场景：
     * <ol>
     *   <li>关联异常流水：entryId 非空。要求流水已被 MARK_EXCEPTION 隔离、eventRef 与隔离事件一致、
     *       补偿金额恰为流水带符号金额的相反数（修复后重新守恒），且该事件只能补偿一次；</li>
   *   <li>单边余额漂移修复：entryId 为空（如外部对账发现余额侧差异）。只调整余额，不产生流水，
     *       修复后不得造成负余额；同一 eventRef 同样只能生效一次。</li>
     * </ol>
     */
    public OperationRecord compensate(OperationContext ctx, String accountId, String entryId,
                                      String eventRef, Long compensationDelta) {
        OperationPayload payload = new OperationPayload();
        payload.setAccountId(accountId);
        payload.setEntryId(entryId);
        payload.setEventRef(eventRef);
        payload.setCompensationDelta(compensationDelta);

        return execute(OperationType.COMPENSATE, ctx, payload, new Mutator() {
            @Override
            public void validate() {
                requireText(accountId, "accountId");
                requireText(eventRef, "eventRef");
                if (compensationDelta == null || compensationDelta == 0L) {
                    throw reject(FailureCode.INVALID_REQUEST,
                            "compensationDelta must be non-zero");
                }
                if (store.isCompensated(eventRef)) {
                    throw reject(FailureCode.COMPENSATION_ALREADY_APPLIED,
                            "event " + eventRef + " already compensated once");
                }
                TenantAccount account = mustGetAccount(ctx.tenantId(), accountId);

                if (entryId != null && !entryId.isBlank()) {
                    LedgerEntry entry = mustGetEntry(ctx.tenantId(), entryId);
                    if (!entry.getAccountId().equals(accountId)) {
                        throw reject(FailureCode.INVALID_REQUEST,
                                "entry " + entryId + " does not belong to account " + accountId);
                    }
                    if (entry.getState() != EntryState.EXCEPTIONAL) {
                        throw reject(FailureCode.INVALID_REQUEST,
                                "entry " + entryId + " is not marked exceptional; "
                                        + "mark it before compensating");
                    }
                    if (entry.getCompensatedBy() != null) {
                        throw reject(FailureCode.COMPENSATION_ALREADY_APPLIED,
                                "entry " + entryId + " already compensated by event "
                                        + entry.getCompensatedBy());
                    }
                    if (!eventRef.equals(entry.getExceptionRef())) {
                        throw reject(FailureCode.INVALID_REQUEST,
                                "eventRef " + eventRef + " does not match isolation event "
                                        + entry.getExceptionRef());
                    }
                    if (compensationDelta != -entry.getSignedDelta()) {
                        throw reject(FailureCode.INVALID_REQUEST,
                                "compensationDelta must reverse the isolated entry delta "
                                        + entry.getSignedDelta());
                    }
                    if (account.getBalance() + compensationDelta < 0) {
                        throw reject(FailureCode.INSUFFICIENT_FUNDS,
                                "compensation would drive account negative: "
                                        + account.getBalance() + compensationDelta);
                    }
                } else {
                    if (account.getBalance() + compensationDelta < 0) {
                        throw reject(FailureCode.INSUFFICIENT_FUNDS,
                                "compensation would drive account negative: "
                                        + account.getBalance() + compensationDelta);
                    }
                }
            }

            @Override
            public void apply() {
                TenantAccount account = store.findAccount(ctx.tenantId(), accountId).orElseThrow();
                account.applySignedDelta(compensationDelta);
                if (entryId != null && !entryId.isBlank()) {
                    store.findEntry(ctx.tenantId(), entryId).orElseThrow()
                            .closeWithCompensation(eventRef);
                }
                store.registerCompensation(new CompensationRecord(ctx.tenantId(), accountId,
                        eventRef, compensationDelta, ctx.idempotencyKey()));
            }
        });
    }

    // ------------------------------------------------------------------
    // 统一执行管道
    // ------------------------------------------------------------------

    private OperationRecord execute(OperationType type, OperationContext ctx,
                                    OperationPayload payload, Mutator mutator) {
        synchronized (opLock) {
            if (ctx == null) {
                throw reject(FailureCode.INVALID_REQUEST, "missing operation context");
            }

            // 幂等：同一 idempotencyKey 的重复/并发请求直接返回首次结论，不再次落盘、不再次生效。
            java.util.Optional<OperationRecord> existing = store.findIdempotent(ctx.idempotencyKey());
            if (existing.isPresent()) {
                OperationRecord first = existing.get();
                log.info("request idempotent-replay requestId={} tenant={} key={} type={} "
                                + "firstSeq={} firstStatus={} firstCode={} basis=idempotencyKey",
                        ctx.requestId(), ctx.tenantId(), ctx.idempotencyKey(), type,
                        first.getSeq(), first.getStatus(), first.getFailureCode());
                return first;
            }

            OperationRecord rec = new OperationRecord();
            rec.setTenantId(ctx.tenantId());
            rec.setRequestId(ctx.requestId());
            rec.setIdempotencyKey(ctx.idempotencyKey());
            rec.setRole(ctx.role());
            rec.setType(type);
            rec.setPayload(toJson(payload));

            try {
                requireContext(ctx);
                checkPermission(type, ctx.role());
                mutator.validate();
                rec.setStatus(OperationStatus.APPLIED);
                rec.setFailureCode(FailureCode.NONE);
            } catch (LedgerException e) {
                rec.setStatus(OperationStatus.REJECTED);
                rec.setFailureCode(e.getFailureCode());
                rec.setFailureReason(e.getMessage());
            }

            // 日志先落盘（APPLIED/REJECTED 都持久化），随后再变更内存态。
            store.appendRecord(rec);

            if (rec.getStatus() == OperationStatus.APPLIED) {
                mutator.apply();
            }
            store.putIdempotent(ctx.idempotencyKey(), rec);

            log.info("request handled requestId={} tenant={} role={} key={} type={} "
                            + "seq={} result={} code={} reason={} basis=validation+permissions",
                    ctx.requestId(), ctx.tenantId(), ctx.role(), ctx.idempotencyKey(), type,
                    rec.getSeq(), rec.getStatus(), rec.getFailureCode(), rec.getFailureReason());
            return rec;
        }
    }

    private void requireContext(OperationContext ctx) {
        requireText(ctx.tenantId(), "tenantId");
        requireText(ctx.requestId(), "requestId");
        requireText(ctx.idempotencyKey(), "idempotencyKey");
        if (ctx.role() == null || ctx.role().isBlank()) {
            throw reject(FailureCode.PERMISSION_DENIED, "missing role");
        }
    }

    private void checkPermission(OperationType type, String role) {
        if (!Roles.isKnown(role)) {
            throw reject(FailureCode.PERMISSION_DENIED, "unknown role: " + role);
        }
        boolean allowed = switch (type) {
            case CREATE_ACCOUNT, ENTRY -> true;
            case MARK_EXCEPTION -> Roles.AUDITOR.equals(role) || Roles.ADMIN.equals(role);
            case COMPENSATE -> Roles.ADMIN.equals(role);
        };
        if (!allowed) {
            throw reject(FailureCode.PERMISSION_DENIED,
                    "role " + role + " may not perform " + type);
        }
    }

    private TenantAccount mustGetAccount(String tenantId, String accountId) {
        return store.findAccount(tenantId, accountId).orElseThrow(() -> {
            if (store.accountExistsInOtherTenant(tenantId, accountId)) {
                return reject(FailureCode.TENANT_MISMATCH,
                        "account " + accountId + " belongs to another tenant");
            }
            return reject(FailureCode.ACCOUNT_NOT_FOUND,
                    "account not found in tenant " + tenantId + ": " + accountId);
        });
    }

    private LedgerEntry mustGetEntry(String tenantId, String entryId) {
        return store.findEntry(tenantId, entryId).orElseThrow(() -> {
            if (store.entryExistsInOtherTenant(tenantId, entryId)) {
                return reject(FailureCode.TENANT_MISMATCH,
                        "entry " + entryId + " belongs to another tenant");
            }
            return reject(FailureCode.ENTRY_NOT_FOUND,
                    "entry not found in tenant " + tenantId + ": " + entryId);
        });
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw reject(FailureCode.INVALID_REQUEST, "missing or blank field: " + field);
        }
    }

    private static LedgerException reject(FailureCode code, String reason) {
        return new LedgerException(code, reason);
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize payload", e);
        }
    }
}
