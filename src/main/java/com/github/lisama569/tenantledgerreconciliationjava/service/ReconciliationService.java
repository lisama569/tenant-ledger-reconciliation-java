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

import java.util.ArrayList;
import java.util.List;

/**
 * 对账与补偿服务。
 *
 * 对账规则：
 *   recomputedBalance = openingBalance + Σ正常CREDIT − Σ正常DEBIT
 *   其中已被补偿的异常流水及其补偿流水互相抵消（−a + +a ≡ 0），
 *   因此“待处理异常 + 已补偿对”两种情形下余额等式都成立；未处理异常造成的金额漂移
 *   会表现为 storedBalance != recomputedBalance（对账不匹配）。
 *
 * 补偿语义：
 *   仅 COMPENSATION_ADMIN 可标记异常/发起补偿；补偿生成一条反向冲销流水，
 *   同一 requestId 幂等、同一异常流水只允许补偿一次，重复处理不再改变任何总和。
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerRepository repo;

    public ReconciliationService(LedgerRepository repository) {
        this.repo = repository;
    }

    // ============================== 对账 ==============================

    /**
     * 对账规则：
     *   recomputedBalance = openingBalance + Σ干净流水(CREDIT 加 / DEBIT 减)
     * “干净流水”排除：被标记为异常的流水，以及补偿流水。
     * - 异常已标记但尚未补偿：账面余额仍含异常资金，与应有干净余额不匹配；
     * - 补偿完成：账面余额被反向冲销拉回干净余额，二者匹配；
     * - 异常与其补偿成对出现且都不计入，补偿后重复对账结论恒定（守恒）。
     */
    public List<AccountCheckResult> reconcileAll() {
        return repo.read(() -> {
            List<AccountCheckResult> results = new ArrayList<>();
            for (Account account : repo.allAccounts()) {
                List<LedgerEntry> entries = repo.entriesByAccount(account.getAccountId());
                long clean = account.getOpeningBalance();
                List<String> anomalies = new ArrayList<>();
                for (LedgerEntry e : entries) {
                    if (e.isAnomaly() || e.isCompensation()) {
                        // 异常资金与其补偿都不计入“应有干净余额”
                        if (e.isAnomaly() && !e.isCompensated() && !e.isCompensation()) {
                            anomalies.add(e.getEntryId());
                        }
                        continue;
                    }
                    clean += e.getDirection() == Direction.CREDIT ? e.getAmount() : -e.getAmount();
                }
                boolean matched = clean == account.getBalance();
                results.add(new AccountCheckResult(account.getAccountId(), account.getTenantId(),
                        account.getBalance(), clean, matched, anomalies));
                log.info("对账账户={} tenant={} 账面余额={} 应有干净余额={} 匹配={} 待补偿异常={}",
                        account.getAccountId(), account.getTenantId(), account.getBalance(), clean,
                        matched, anomalies);
            }
            return results;
        });
    }

    // ============================== 标记异常 ==============================

    public LedgerEntry markAnomaly(String entryId, String requestId, RequestContext ctx) {
        requireAdmin(OperationType.MARK_ANOMALY, requestId, ctx);
        return repo.mutate(() -> {
            if (repo.findAppliedByRequestId(requestId).isPresent()) {
                log.info("requestId={} 结果=REJECTED(DUPLICATE_REQUEST) 判定=标记异常幂等命中，不重复处理",
                        requestId);
                throw rejectAudit(OperationType.MARK_ANOMALY, requestId, ctx,
                        FailureReason.DUPLICATE_REQUEST, "重复请求，幂等键已存在: " + requestId,
                        null, null, 0, entryId);
            }
            LedgerEntry entry = repo.findEntry(entryId).orElse(null);
            if (entry == null) {
                throw rejectAudit(OperationType.MARK_ANOMALY, requestId, ctx,
                        FailureReason.ANOMALY_TARGET_MISSING, "流水不存在: " + entryId,
                        null, null, 0, entryId);
            }
            if (entry.isCompensation()) {
                throw rejectAudit(OperationType.MARK_ANOMALY, requestId, ctx,
                        FailureReason.INVALID_OPERATION, "补偿流水本身不能被标记为异常",
                        entry.getAccountId(), entry.getTenantId(), entry.getAmount(), entryId);
            }
            if (entry.isAnomaly()) {
                log.info("requestId={} 结果=幂等 标记异常 {} 已是异常态，不重复处理", requestId, entryId);
                return entry;
            }
            OperationRecord rec = buildRecord(OperationType.MARK_ANOMALY, requestId, ctx,
                    payload(entry.getAccountId(), entry.getTenantId(), 0, entryId));
            rec.setStatus(OperationStatus.APPLIED);
            repo.appendOperation(rec);
            entry.setAnomaly(true);
            log.info("requestId={} 结果=APPLIED 操作=MARK_ANOMALY 判定=流水 {} 标记为异常（不改变余额）",
                    requestId, entryId);
            return entry;
        });
    }

    // ============================== 补偿 ==============================

    public LedgerEntry compensate(String entryId, String requestId, RequestContext ctx) {
        requireAdmin(OperationType.COMPENSATE, requestId, ctx);
        return repo.mutate(() -> {
            LedgerEntry existing = repo.findAppliedByRequestId(requestId)
                    .map(r -> repo.findEntry(LedgerService.entryIdOf(r.getSeq())).orElse(null))
                    .orElse(null);
            if (existing != null && existing.isCompensation()) {
                log.info("requestId={} 结果=幂等 补偿重复提交，返回原补偿流水 {}，不再冲销",
                        requestId, existing.getEntryId());
                return existing;
            }
            LedgerEntry target = repo.findEntry(entryId).orElse(null);
            if (target == null) {                throw rejectAudit(OperationType.COMPENSATE, requestId, ctx,
                        FailureReason.ANOMALY_TARGET_MISSING, "流水不存在: " + entryId,
                        null, null, 0, entryId);
            }
            if (target.isCompensation()) {
                throw rejectAudit(OperationType.COMPENSATE, requestId, ctx,
                        FailureReason.INVALID_OPERATION, "补偿流水不能再次补偿",
                        target.getAccountId(), target.getTenantId(), target.getAmount(), entryId);
            }
            if (!target.isAnomaly()) {
                throw rejectAudit(OperationType.COMPENSATE, requestId, ctx,
                        FailureReason.ENTRY_NOT_ANOMALY, "目标流水未被标记为异常，拒绝补偿: " + entryId,
                        target.getAccountId(), target.getTenantId(), target.getAmount(), entryId);
            }
            if (target.isCompensated()) {
                throw rejectAudit(OperationType.COMPENSATE, requestId, ctx,
                        FailureReason.ENTRY_ALREADY_COMPENSATED, "异常流水已补偿，拒绝重复补偿: " + entryId,
                        target.getAccountId(), target.getTenantId(), target.getAmount(), entryId);
            }

            // 反向冲销：原 CREDIT 则补偿 DEBIT，反之亦然；金额相同
            Direction reverse = target.getDirection() == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
            OperationRecord rec = buildRecord(OperationType.COMPENSATE, requestId, ctx,
                    payload(target.getAccountId(), target.getTenantId(), target.getAmount(), entryId));
            rec.getPayload().setDirection(reverse);
            rec.setStatus(OperationStatus.APPLIED);
            repo.appendOperation(rec);

            target.setCompensated(true);
            LedgerEntry comp = applyCompensationEffect(rec, entryId);
            log.info("requestId={} 结果=APPLIED 操作=COMPENSATE 判定=异常 {} 一次性反向冲销 {} {}，补偿流水={}",
                    requestId, entryId, reverse, target.getAmount(), comp.getEntryId());
            return comp;
        });
    }

    /** 补偿流水落账：与普通 ENTRY 相同的余额规则，但标记为 compensation 并关联原流水。 */
    private LedgerEntry applyCompensationEffect(OperationRecord rec, String referenceId) {
        OperationPayload p = rec.getPayload();
        Account account = repo.findAccount(p.getAccountId()).orElseThrow();
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryId(LedgerService.entryIdOf(rec.getSeq()));
        entry.setAccountId(p.getAccountId());
        entry.setTenantId(p.getTenantId());
        entry.setDirection(p.getDirection());
        entry.setAmount(p.getAmount());
        entry.setRequestId(rec.getRequestId());
        entry.setReferenceId(referenceId);
        entry.setCompensation(true);
        repo.putEntry(entry);
        account.setBalance(account.getBalance()
                + (p.getDirection() == Direction.CREDIT ? p.getAmount() : -p.getAmount()));
        return entry;
    }

    // ============================== 权限与拒绝审计 ==============================

    private void requireAdmin(OperationType type, String requestId, RequestContext ctx) {
        if (!repo.isHealthy()) {
            throw new ServiceException(FailureReason.LEDGER_UNHEALTHY,
                    "账本处于只读保护状态: " + repo.getUnhealthyReason());
        }
        if (ctx == null || ctx.role() != Role.COMPENSATION_ADMIN) {
            // 权限拒绝同样进入写锁留审计记录
            repo.mutate(() -> {
                throw rejectAudit(type, requestId, ctx,
                        FailureReason.PERMISSION_DENIED, "仅 COMPENSATION_ADMIN 可执行对账补偿操作",
                        null, null, 0, null);
            });
        }
    }

    private ServiceException rejectAudit(OperationType type, String requestId, RequestContext ctx,
                                         FailureReason reason, String message, String accountId,
                                         String tenantId, long amount, String referenceId) {
        OperationRecord rec = buildRecord(type, requestId, ctx,
                payload(accountId, tenantId, amount, referenceId));
        rec.setStatus(OperationStatus.REJECTED);
        rec.setFailureReason(reason);
        repo.appendOperation(rec);
        log.info("requestId={} 结果=REJECTED({}) 操作={} 判定={}", requestId, reason, type, message);
        return new ServiceException(reason, message + " (requestId=" + requestId + ")");
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

    private OperationPayload payload(String accountId, String tenantId, long amount, String referenceId) {
        OperationPayload p = new OperationPayload();
        p.setAccountId(accountId);
        p.setTenantId(tenantId);
        p.setAmount(amount);
        p.setReferenceId(referenceId);
        return p;
    }

    /** 单账户对账结论。 */
    public record AccountCheckResult(String accountId, String tenantId, long storedBalance,
                                    long recomputedBalance, boolean matched,
                                    List<String> anomalyEntryIds) {
    }
}
