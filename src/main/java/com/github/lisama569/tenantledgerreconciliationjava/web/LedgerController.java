package com.github.lisama569.tenantledgerreconciliationjava.web;

import com.github.lisama569.tenantledgerreconciliationjava.domain.EntryType;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureCode;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerException;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationRecord;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReconciliationReport;
import com.github.lisama569.tenantledgerreconciliationjava.domain.TenantAccount;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReconciliationService;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import com.github.lisama569.tenantledgerreconciliationjava.storage.ReplayReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地 HTTP 接口。所有写接口都要求在请求体中携带租户、角色、请求标识与幂等键，
 * 失败时统一返回 {@code {result, failureCode, failureReason, ...}} 的可区分错误结构。
 */
@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final LocalLedgerStore store;

    public LedgerController(LedgerService ledgerService,
                            ReconciliationService reconciliationService,
                            LocalLedgerStore store) {
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
        this.store = store;
    }

    public record CreateAccountRequest(
            String tenantId, String role, String requestId, String idempotencyKey,
            String accountId, Long initialBalance) {
    }

    public record EntryRequest(
            String tenantId, String role, String requestId, String idempotencyKey,
            String accountId, String entryId, String entryType, Long amount) {
    }

    public record MarkExceptionRequest(
            String tenantId, String role, String requestId, String idempotencyKey,
            String accountId, String entryId, String eventRef) {
    }

    public record CompensateRequest(
            String tenantId, String role, String requestId, String idempotencyKey,
            String accountId, String entryId, String eventRef, Long compensationDelta) {
    }

    public record OperationResponse(
            String result, String requestId, String idempotencyKey, long seq,
            String type, String failureCode, String failureReason, boolean idempotentReplay) {

        static OperationResponse of(OperationRecord rec, boolean replay) {
            return new OperationResponse(
                    rec.getStatus().name(), rec.getRequestId(), rec.getIdempotencyKey(),
                    rec.getSeq(), rec.getType().name(),
                    rec.getStatus() == com.github.lisama569.tenantledgerreconciliationjava.domain.OperationStatus.REJECTED
                            ? rec.getFailureCode().name() : FailureCode.NONE.name(),
                    rec.getFailureReason(), replay);
        }
    }

    @PostMapping("/accounts")
    public OperationResponse createAccount(@RequestBody CreateAccountRequest req) {
        OperationRecord rec = ledgerService.createAccount(context(req.tenantId(), req.role(),
                req.requestId(), req.idempotencyKey()), req.accountId(), req.initialBalance());
        return respond(rec, req.requestId());
    }

    @PostMapping("/entries")
    public OperationResponse postEntry(@RequestBody EntryRequest req) {
        EntryType type = parseEntryType(req.entryType());
        long amount = req.amount() == null ? 0L : req.amount();
        OperationRecord rec = ledgerService.postEntry(
                context(req.tenantId(), req.role(), req.requestId(), req.idempotencyKey()),
                req.accountId(), req.entryId(), type, amount);
        return respond(rec, req.requestId());
    }

    @PostMapping("/exceptions")
    public OperationResponse markException(@RequestBody MarkExceptionRequest req) {
        OperationRecord rec = ledgerService.markException(
                context(req.tenantId(), req.role(), req.requestId(), req.idempotencyKey()),
                req.accountId(), req.entryId(), req.eventRef());
        return respond(rec, req.requestId());
    }

    @PostMapping("/compensations")
    public OperationResponse compensate(@RequestBody CompensateRequest req) {
        OperationRecord rec = ledgerService.compensate(
                context(req.tenantId(), req.role(), req.requestId(), req.idempotencyKey()),
                req.accountId(), req.entryId(), req.eventRef(), req.compensationDelta());
        return respond(rec, req.requestId());
    }

    @GetMapping("/tenants/{tenantId}/accounts/{accountId}")
    public TenantAccount getAccount(@PathVariable String tenantId,
                                    @PathVariable String accountId) {
        return store.findAccount(tenantId, accountId)
                .orElseThrow(() -> {
                    if (store.accountExistsInOtherTenant(tenantId, accountId)) {
                        return new LedgerException(FailureCode.TENANT_MISMATCH,
                                "account belongs to another tenant");
                    }
                    return new LedgerException(FailureCode.ACCOUNT_NOT_FOUND, "account not found");
                });
    }

    @GetMapping("/tenants/{tenantId}/accounts/{accountId}/entries")
    public Object listEntries(@PathVariable String tenantId, @PathVariable String accountId) {
        if (store.findAccount(tenantId, accountId).isEmpty()) {
            throw new LedgerException(FailureCode.ACCOUNT_NOT_FOUND, "account not found");
        }
        return store.entriesOf(tenantId, accountId);
    }

    @GetMapping("/tenants/{tenantId}/reconciliation")
    public ReconciliationReport reconcile(@PathVariable String tenantId) {
        return reconciliationService.reconcile(tenantId);
    }

    @GetMapping("/replay-report")
    public ReplayReport replayReport() {
        return store.getLastReplayReport();
    }

    // ------------------------------------------------------------------

    private static OperationContext context(String tenantId, String role,
                                            String requestId, String idempotencyKey) {
        return new OperationContext(tenantId, role, requestId, idempotencyKey);
    }

    private static EntryType parseEntryType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LedgerException(FailureCode.INVALID_REQUEST, "entryType is required");
        }
        try {
            return EntryType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LedgerException(FailureCode.INVALID_REQUEST, "unknown entryType: " + raw);
        }
    }

    /**
     * REJECTED 记录（含权限/余额/租户等失败）转为异常交给全局处理器；
     * 如何区分"首次拒绝"与"幂等重放返回首次结论"：当前请求 id 与首次记录 requestId 不同。
     */
    private OperationResponse respond(OperationRecord rec, String currentRequestId) {
        boolean replay = currentRequestId != null
                && !currentRequestId.equals(rec.getRequestId());
        return OperationResponse.of(rec, replay);
    }
}
