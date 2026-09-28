package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.github.lisama569.tenantledgerreconciliationjava.domain.RequestContext;
import com.github.lisama569.tenantledgerreconciliationjava.domain.Role;
import com.github.lisama569.tenantledgerreconciliationjava.service.LedgerService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReconciliationService;
import com.github.lisama569.tenantledgerreconciliationjava.service.ReplayService;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerRepository;

import java.nio.file.Path;

/** 测试装配：在临时文件上构建一套服务（不启动 Spring 容器）。 */
final class TestSupport {

    private TestSupport() {
    }

    record Harness(Path file, LedgerRepository repo, LedgerService ledger,
                   ReconciliationService recon, ReplayService replay) {

        /** 模拟服务重启：新建仓库（从同一文件加载）并重放。 */
        Harness restart() {
            return build(file, true);
        }
    }

    static Harness build(Path file, boolean replayOnStart) {
        LedgerRepository repo = new LedgerRepository(file);
        LedgerService ledger = new LedgerService(repo);
        ReconciliationService recon = new ReconciliationService(repo);
        ReplayService replay = new ReplayService(repo, ledger);
        if (replayOnStart) {
            replay.replay();
        }
        return new Harness(file, repo, ledger, recon, replay);
    }

    static Harness fresh(Path file) {
        return build(file, false);
    }

    static RequestContext operator(String tenant) {
        return new RequestContext(Role.TENANT_OPERATOR, tenant);
    }

    static RequestContext admin() {
        return new RequestContext(Role.COMPENSATION_ADMIN, null);
    }
}
