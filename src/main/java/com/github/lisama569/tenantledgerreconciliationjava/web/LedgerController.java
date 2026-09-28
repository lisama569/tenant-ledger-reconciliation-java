package com.github.lisama569.tenantledgerreconciliationjava.web;

import com.github.lisama569.tenantledgerreconciliationjava.domain.Account;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Direction;
import com.github.lisama569.tenantledgerreconciliationjava.domain.FailureReason;
import com.github.lisama569.tenantledgerreconciliationjava.domain.LedgerEntry;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ReplayReport;
import com.github.lisama569.tenantledgerreconciliationjava.domain.RequestContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Role;
import com.github.lisama569.tenantledgerreconciliationjava.domain.ServiceException;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReconciliationService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReplayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 账本 REST 接口。
 * 以请求头模拟鉴权与幂等：
 *   X-Role: TENANT_OPERATOR | COMPENSATION_ADMIN
 *   X-Tenant-Id: 操作员必填且必须与请求体租户一致；管理员可空
 *   X-Request-Id: 业务幂等键，缺省由服务端生成（客户端重试应显式携带）
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final ReplayService replayService;

    public LedgerController(LedgerService ledgerService,
                            ReconciliationService reconciliationService,
                            ReplayService replayService) {
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
        this.replayService = replayService;
    }

    @PostMapping("/accounts")
    public Account openAccount(@RequestBody OpenAccountRequest req,
                               @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                               @RequestHeader("X-Role") String role,
                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        RequestContext ctx = context(role, tenantId);
        long opening = req.openingBalance() == null ? 0L : req.openingBalance();
        return ledgerService.openAccount(req.accountId(), req.tenantId(), opening,
                ensureRequestId(requestId), ctx);
    }

    @GetMapping("/accounts/{accountId}")
    public Account getAccount(@PathVariable String accountId,
                              @RequestHeader("X-Role") String role,
                              @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return ledgerService.getAccount(accountId, context(role, tenantId));
    }

    @GetMapping("/accounts/{accountId}/entries")
    public List<LedgerEntry> getEntries(@PathVariable String accountId,
                                        @RequestHeader("X-Role") String role,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return ledgerService.getEntries(accountId, context(role, tenantId));
    }

    @PostMapping("/entries")
    public LedgerEntry submitEntry(@RequestBody EntryRequest req,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                   @RequestHeader("X-Role") String role,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        RequestContext ctx = context(role, tenantId);
        long amount = req.amount() == null ? 0L : req.amount();
        return ledgerService.submitEntry(req.accountId(), req.tenantId(), req.direction(), amount,
                ensureRequestId(requestId), ctx);
    }

    @PostMapping("/entries/{entryId}/anomaly")
    public LedgerEntry markAnomaly(@PathVariable String entryId,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                   @RequestHeader("X-Role") String role,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return reconciliationService.markAnomaly(entryId, ensureRequestId(requestId),
                context(role, tenantId));
    }

    @PostMapping("/entries/{entryId}/compensate")
    public LedgerEntry compensate(@PathVariable String entryId,
                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                  @RequestHeader("X-Role") String role,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return reconciliationService.compensate(entryId, ensureRequestId(requestId),
                context(role, tenantId));
    }

    @GetMapping("/reconcile")
    public List<ReconciliationService.AccountCheckResult> reconcile() {
        return reconciliationService.reconcileAll();
    }

    @PostMapping("/replay")
    public ReplayReport replay(@RequestHeader("X-Role") String role,
                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        RequestContext ctx = context(role, tenantId);
        if (ctx.role() != Role.COMPENSATION_ADMIN) {
            throw new ServiceException(FailureReason.PERMISSION_DENIED, "仅 COMPENSATION_ADMIN 可手动触发重放");
        }
        return replayService.replay();
    }

    @GetMapping("/health")
    public ReplayReport health() {
        return replayService.lastReport();
    }

    // ---- helpers ----

    private static RequestContext context(String roleHeader, String tenantHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw new IllegalArgumentException("缺少 X-Role 请求头");
        }
        Role role;
        try {
            role = Role.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知角色: " + roleHeader);
        }
        if (role == Role.TENANT_OPERATOR && (tenantHeader == null || tenantHeader.isBlank())) {
            throw new IllegalArgumentException("TENANT_OPERATOR 必须携带 X-Tenant-Id");
        }
        return new RequestContext(role, tenantHeader);
    }

    private static String ensureRequestId(String requestId) {
        return (requestId == null || requestId.isBlank())
                ? "auto-" + UUID.randomUUID()
                : requestId;
    }

    // ---- DTO ----
    public record OpenAccountRequest(String accountId, String tenantId, Long openingBalance) {
    }

    public record EntryRequest(String accountId, String tenantId, Direction direction, Long amount) {
    }
}
