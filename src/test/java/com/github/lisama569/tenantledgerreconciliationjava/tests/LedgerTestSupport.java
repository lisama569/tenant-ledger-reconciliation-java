package com.github.lisama569.tenantledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.domain.OperationContext;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;

import java.nio.file.Path;
import java.util.UUID;

/** 测试公共工具：临时目录、ObjectMapper、请求上下文。 */
final class LedgerTestSupport {

    private LedgerTestSupport() {
    }

    static LocalLedgerStore newStore(Path baseDir, String name) {
        Path dir = baseDir.resolve(name + "-" + UUID.randomUUID());
        return new LocalLedgerStore(dir, new ObjectMapper());
    }

    static OperationContext ctx(String tenant, String role, String requestId, String key) {
        return new OperationContext(tenant, role, requestId, key);
    }
}
