package com.github.lisama569.tenantledgerreconciliationjava.service;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Account;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Direction;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureReason;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationPayload;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.RequestContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Role;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 账本核心服务。
 *
 * 一致性要点：
 * 1) 所有变更在 {@link LedgerRepository#mutate} 的单一写锁内完成，
 *    “校验 + 追加操作日志（含哈希链）+ 改派生余额 + 落盘”是一个原子临界区；
 * 2) requestId 为业务幂等键：同一 requestId 的成功操作只生效一次，
 *    后续重复提交返回原始结果（DUPLICATE_REQUEST 拒绝另记审计日志）；
 * 3) 被拒绝的请求同样写入操作日志（status=REJECTED + 原因），便于解释与审计；
 * 4) 余额按分（long）计算，方向不可逆地约束增减。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repository) {
        this.repo = repository;
    }

    /** 派生流水的确定性 ID：由操作序号决定，重放时可重现。 */
    public static String entryIdOf(long opSeq) {
        return "E-" + opSeq;
    }

    // ============================== 开户 ==============================

    public Account openAccount(String accountId, String tenantId, long openingBalance,
                               String requestId, RequestContext ctx) {
        requireHealthy();
        validateIdentity(accountId, tenantId);
        if (openingBalance < 0) {
            return reject(null, OperationType.OPEN_ACCOUNT, requestId, ctx,
                    FailureReason.INVALID_AMOUNT,
                    "开户初始余额不能为负: " + openingBalance, accountId, tenantId, null, 0);
        }
        return repo.mutate(() -> {
            if (ctx == null || ctx.role() != Role.TENANT_OPERATOR) {
                throw rejected(OperationType.OPEN_ACCOUNT, requestId, ctx,
                        FailureReason.PERMISSION_DENIED, "仅 TENANT_OPERATOR 可执行开户",
                        accountId, tenantId, null, openingBalance);
            }
            enforceCallerTenant(OperationType.OPEN_ACCOUNT, requestId, ctx, tenantId,
                    accountId, null, openingBalance);
            if (repo.findAccount(accountId).isPresent()) {
                return rejectLocked(null, OperationType.OPEN_ACCOUNT, requestId, ctx,
                        FailureReason.INVALID_OPERATION, "账户已存在: " + accountId,
                        accountId, tenantId, null, openingBalance);
            }
            Account account = new Account();
            account.setAccountId(accountId);
            account.setTenantId(tenantId);
            account.setOpeningBalance(openingBalance);
            account.setBalance(openingBalance);
            account.setClosed(false);
            repo.putAccount(account);

            OperationRecord rec = buildRecord(OperationType.OPEN_ACCOUNT, requestId, ctx,
                    payload(accountId, tenantId, null, openingBalance, null));
            rec.setStatus(OperationStatus.APPLIED);
            repo.appendOperation(rec);

            log.info("requestId={} 结果=APPLIED 操作=OPEN_ACCOUNT 判定=开户成功 account={} tenant={} 初始余额={}",
                    requestId, accountId, tenantId, openingBalance);
            return account;
        });
    }

    // ============================== 资金变动 ==============================

    public LedgerEntry submitEntry(String accountId, String tenantId, Direction direction,
                                   long amount, String requestId, RequestContext ctx) {
        requireHealthy();
        requireTenantOperator(ctx);
        validateIdentity(accountId, tenantId);
        if (direction == null) {
            return reject(null, OperationType.ENTRY, requestId, ctx,
                    FailureReason.INVALID_OPERATION, "direction 缺失", accountId, tenantId, null, amount);
        }
        if (amount <= 0) {
            return reject(null, OperationType.ENTRY, requestId, ctx,
                    FailureReason.INVALID_AMOUNT, "金额必须为正数: " + amount,
                    accountId, tenantId, direction, amount);
        }
        return repo.mutate(() -> {
            if (ctx == null || ctx.role() != Role.TENANT_OPERATOR) {
                throw rejected(OperationType.ENTRY, requestId, ctx,
                        FailureReason.PERMISSION_DENIED, "仅 TENANT_OPERATOR 可提交资金变动",
                        accountId, tenantId, direction, amount);
            }
            enforceCallerTenant(OperationType.ENTRY, requestId, ctx, tenantId,
                    accountId, direction, amount);
            // 幂等：同一 requestId 的成功操作直接返回原流水，不重复入账
            Optional<OperationRecord> existing = repo.findAppliedByRequestId(requestId);
            if (existing.isPresent()) {
                log.info("requestId={} 结果=REJECTED(DUPLICATE_REQUEST) 判定=幂等命中 seq={}，原流水 {} 不重复入账",
                        requestId, existing.get().getSeq(), entryIdOf(existing.get().getSeq()));
                throw rejected(OperationType.ENTRY, requestId, ctx, FailureReason.DUPLICATE_REQUEST,
                        "重复请求，幂等键已存在: " + requestId,
                        accountId, tenantId, direction, amount);
            }
            Account account = repo.findAccount(accountId).orElse(null);
            if (account == null) {
                return rejectLocked(null, OperationType.ENTRY, requestId, ctx,
                        FailureReason.UNKNOWN_ACCOUNT, "账户不存在: " + accountId,
                        accountId, tenantId, direction, amount);
            }
            if (!tenantId.equals(account.getTenantId())) {
                return rejectLocked(null, OperationType.ENTRY, requestId, ctx,
                        FailureReason.TENANT_MISMATCH,
                        "租户不匹配: 请求租户=" + tenantId + "，账户租户=" + account.getTenantId(),
                        accountId, tenantId, direction, amount);
            }
            if (account.isClosed()) {
                return rejectLocked(null, OperationType.ENTRY, requestId, ctx,
                        FailureReason.ACCOUNT_CLOSED, "账户已关闭: " + accountId,
                        accountId, tenantId, direction, amount);
            }
            if (direction == Direction.DEBIT && account.getBalance() < amount) {
                return rejectLocked(null, OperationType.ENTRY, requestId, ctx,
                        FailureReason.INSUFFICIENT_FUNDS,
                        "余额不足: 当前=" + account.getBalance() + "，请求扣款=" + amount,
                        accountId, tenantId, direction, amount);
            }

            OperationRecord rec = buildRecord(OperationType.ENTRY, requestId, ctx,
                    payload(accountId, tenantId, direction, amount, null));
            rec.setStatus(OperationStatus.APPLIED);
            repo.appendOperation(rec);

            LedgerEntry entry = applyEntryEffect(rec, false, null, false, false);
            log.info("requestId={} 结果=APPLIED 操作=ENTRY 判定=校验通过并落账 seq={} entry={} {} {} 余额={}",
                    requestId, rec.getSeq(), entry.getEntryId(), direction, amount, account.getBalance());
            return entry;
        });
    }

    // ============================== 查询 ==============================

    public Account getAccount(String accountId, RequestContext ctx) {
        return repo.read(() -> {
            Account account = repo.findAccount(accountId)
                    .orElseThrow(() -> new ServiceException(FailureReason.UNKNOWN_ACCOUNT, "账户不存在: " + accountId));
            enforceTenantRead(ctx, account.getTenantId());
            return account;
        });
    }

    public List<LedgerEntry> getEntries(String accountId, RequestContext ctx) {
        return repo.read(() -> {
            Account account = repo.findAccount(accountId)
                    .orElseThrow(() -> new ServiceException(FailureReason.UNKNOWN_ACCOUNT, "账户不存在: " + accountId));
            enforceTenantRead(ctx, account.getTenantId());
            return repo.entriesByAccount(accountId);
        });
    }

    public List<OperationRecord> getOperationLog() {
        return repo.read(repo::allOperations);
    }

    // ============================== 派生效果（实时与重放共用）==============================

    /**
     * 将一条 APPLIED 操作的账务效果作用于派生状态（账户余额/流水）。
     * 实时入账与重启重放走同一套逻辑，保证结论一致。必须在写锁内调用。
     */
    public LedgerEntry applyRecordEffects(OperationRecord rec) {
        OperationPayload p = rec.getPayload();
        return switch (rec.getType()) {
            case OPEN_ACCOUNT -> {
                if (repo.findAccount(p.getAccountId()).isEmpty()) {
                    Account account = new Account();
                    account.setAccountId(p.getAccountId());
                    account.setTenantId(p.getTenantId());
                    account.setOpeningBalance(p.getAmount());
                    account.setBalance(p.getAmount());
                    repo.putAccount(account);
                }
                yield null;
            }
            case ENTRY -> applyEntryEffect(rec, false, null, false, false);
            case MARK_ANOMALY -> {
                LedgerEntry e = repo.findEntry(p.getReferenceId()).orElseThrow();
                e.setAnomaly(true);
                yield e;
            }
            case COMPENSATE -> {
                LedgerEntry target = repo.findEntry(p.getReferenceId()).orElseThrow();
                target.setAnomaly(true);
                target.setCompensated(true);
                yield applyEntryEffect(rec, true, target.getEntryId(), false, true);
            }
        };
    }

    private LedgerEntry applyEntryEffect(OperationRecord rec, boolean isCompensation,
                                         String referenceId, boolean anomaly, boolean markTarget) {
        OperationPayload p = rec.getPayload();
        Account account = repo.findAccount(p.getAccountId()).orElseThrow();
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryId(entryIdOf(rec.getSeq()));
        entry.setAccountId(p.getAccountId());
        entry.setTenantId(p.getTenantId());
        entry.setDirection(p.getDirection());
        entry.setAmount(p.getAmount());
        entry.setRequestId(rec.getRequestId());
        entry.setReferenceId(referenceId);
        entry.setAnomaly(anomaly);
        entry.setCompensation(isCompensation);
        repo.putEntry(entry);

        long delta = p.getDirection() == Direction.CREDIT ? p.getAmount() : -p.getAmount();
        account.setBalance(account.getBalance() + delta);
        return entry;
    }

    // ============================== 拒绝处理 ==============================

    /** 锁外前置校验失败：仍需进入写锁追加 REJECTED 审计记录。 */
    private <T> T reject(T ignored, OperationType type, String requestId, RequestContext ctx,
                         FailureReason reason, String message, String accountId, String tenantId,
                         Direction direction, long amount) {
        return repo.mutate(() -> rejectLocked(ignored, type, requestId, ctx, reason, message,
                accountId, tenantId, direction, amount));
    }

    /** 写锁内追加 REJECTED 记录并抛出业务异常（mutate 会保证落盘）。 */
    private <T> T rejectLocked(T ignored, OperationType type, String requestId, RequestContext ctx,
                               FailureReason reason, String message, String accountId, String tenantId,
                               Direction direction, long amount) {
        throw rejected(type, requestId, ctx, reason, message, accountId, tenantId, direction, amount);
    }

    private ServiceException rejected(OperationType type, String requestId, RequestContext ctx,
                                      FailureReason reason, String message, String accountId,
                                      String tenantId, Direction direction, long amount) {
        OperationRecord rec = buildRecord(type, requestId, ctx,
                payload(accountId, tenantId, direction, amount, null));
        rec.setStatus(OperationStatus.REJECTED);
        rec.setFailureReason(reason);
        repo.appendOperation(rec);
        log.info("requestId={} 结果=REJECTED({}) 操作={} 判定={} account={} tenant={}",
                requestId, reason, type, message, accountId, tenantId);
        return new ServiceException(reason, message + " (requestId=" + requestId + ")");
    }

    // ============================== 公共校验（供对账服务复用）==============================

    public void requireHealthy() {
        if (!repo.isHealthy()) {
            throw new ServiceException(FailureReason.LEDGER_UNHEALTHY,
                    "账本处于只读保护状态: " + repo.getUnhealthyReason());
        }
    }

    public static void requireTenantOperator(RequestContext ctx) {
        if (ctx == null || ctx.role() != Role.TENANT_OPERATOR) {
            throw new ServiceException(FailureReason.PERMISSION_DENIED,
                    "仅 TENANT_OPERATOR 可执行该操作");
        }
    }

    /** 租户操作员只能以自己所属租户身份发起业务（头租户必须与请求体租户一致）。 */
    private void enforceCallerTenant(OperationType type, String requestId, RequestContext ctx,
                                     String bodyTenantId, String accountId,
                                     Direction direction, long amount) {
        if (ctx.role() == Role.TENANT_OPERATOR
                && (ctx.tenantId() == null || !ctx.tenantId().equals(bodyTenantId))) {
            throw rejected(type, requestId, ctx, FailureReason.TENANT_MISMATCH,
                    "调用方租户与请求体租户不一致: " + ctx.tenantId() + " != " + bodyTenantId,
                    accountId, bodyTenantId, direction, amount);
        }
    }

    public static void enforceTenantRead(RequestContext ctx, String resourceTenant) {
        if (ctx == null) {
            throw new ServiceException(FailureReason.PERMISSION_DENIED, "缺少调用方上下文");
        }
        if (ctx.role() == Role.TENANT_OPERATOR && !resourceTenant.equals(ctx.tenantId())) {
            throw new ServiceException(FailureReason.TENANT_MISMATCH,
                    "租户不匹配: 调用方租户=" + ctx.tenantId() + "，资源租户=" + resourceTenant);
        }
    }

    private static void validateIdentity(String accountId, String tenantId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
    }

    private OperationRecord buildRecord(OperationType type, String requestId, RequestContext ctx,
                                        OperationPayload payload) {
        OperationRecord rec = new OperationRecord();
        rec.setRequestId(requestId);
        rec.setType(type);
        rec.setRequestedRole(ctx == null ? null : ctx.role());
        rec.setPayload(payload);
        return rec;
    }

    private OperationPayload payload(String accountId, String tenantId, Direction direction,
                                     long amount, String referenceId) {
        OperationPayload p = new OperationPayload();
        p.setAccountId(accountId);
        p.setTenantId(tenantId);
        p.setDirection(direction);
        p.setAmount(amount);
        p.setReferenceId(referenceId);
        return p;
    }
}
