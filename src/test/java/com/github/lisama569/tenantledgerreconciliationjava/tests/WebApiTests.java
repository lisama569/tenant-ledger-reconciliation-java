package com.github.lisama569.tenantledgerreconciliationjava.tests;


import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层集成测试：请求标识/幂等键、处理结果信封、权限与租户边界、对账报告。
 * 使用固定临时目录，日志文件在测试方法间累积（同一应用上下文）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTests {

    /** 每次测试运行使用全新目录，杜绝旧操作日志经启动重放带来的幂等键残留。 */
    static final String DATA_DIR =
            "target/ledger-test-data/run-" + java.util.UUID.randomUUID();

    @DynamicPropertySource
    static void ledgerDir(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("ledger.data-dir", () -> DATA_DIR);
    }


    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullJourneyOverHttp() throws Exception {
        // 开户 t1/a1 初始 500
        mockMvc.perform(post("/api/accounts").contentType("application/json").content("""
                {"tenantId":"t1","role":"USER","requestId":"w1","idempotencyKey":"wk1",
                 "accountId":"wa1","initialBalance":500}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.seq").value(org.hamcrest.Matchers.greaterThan(0)));

        // 借方 200 成功
        mockMvc.perform(post("/api/entries").contentType("application/json").content("""
                {"tenantId":"t1","role":"USER","requestId":"w2","idempotencyKey":"wk2",
                 "accountId":"wa1","entryId":"we1","entryType":"DEBIT","amount":200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        // 重复提交：不同 requestId、同一 idempotencyKey -> 首次 seq，标记幂等重放
        mockMvc.perform(post("/api/entries").contentType("application/json").content("""
                {"tenantId":"t1","role":"USER","requestId":"w2-retry","idempotencyKey":"wk2",
                 "accountId":"wa1","entryId":"we1","entryType":"DEBIT","amount":200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 余额 300 且只有两条流水（OPENING + we1）
        mockMvc.perform(get("/api/tenants/t1/accounts/wa1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300));

        // 权限拒绝：USER 标记异常
        mockMvc.perform(post("/api/exceptions").contentType("application/json").content("""
                {"tenantId":"t1","role":"USER","requestId":"w3","idempotencyKey":"wk3",
                 "accountId":"wa1","entryId":"we1","eventRef":"WEVT1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("PERMISSION_DENIED"));

        // 跨租户：t2 访问 t1 的账户
        mockMvc.perform(post("/api/entries").contentType("application/json").content("""
                {"tenantId":"t2","role":"USER","requestId":"w4","idempotencyKey":"wk4",
                 "accountId":"wa1","entryId":"we2","entryType":"CREDIT","amount":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("TENANT_MISMATCH"));

        // AUDITOR 标记异常成功
        mockMvc.perform(post("/api/exceptions").contentType("application/json").content("""
                {"tenantId":"t1","role":"AUDITOR","requestId":"w5","idempotencyKey":"wk5",
                 "accountId":"wa1","entryId":"we1","eventRef":"WEVT1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        // 对账不匹配：balance=300（隔离不改余额），normalSum=500
        mockMvc.perform(get("/api/tenants/t1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.accounts[0].difference").value(-200))
                .andExpect(jsonPath("$.accounts[0].exceptionalEntryIds[0]")
                        .value(org.hamcrest.Matchers.containsString("we1")));

        // AUDITOR 不能补偿
        mockMvc.perform(post("/api/compensations").contentType("application/json").content("""
                {"tenantId":"t1","role":"AUDITOR","requestId":"w6","idempotencyKey":"wk6",
                 "accountId":"wa1","entryId":"we1","eventRef":"WEVT1","compensationDelta":200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCode").value("PERMISSION_DENIED"));

        // ADMIN 补偿恢复守恒
        mockMvc.perform(post("/api/compensations").contentType("application/json").content("""
                {"tenantId":"t1","role":"ADMIN","requestId":"w7","idempotencyKey":"wk7",
                 "accountId":"wa1","entryId":"we1","eventRef":"WEVT1","compensationDelta":200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        mockMvc.perform(get("/api/tenants/t1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));

        // 重复补偿：拒绝且余额不变（500）
        mockMvc.perform(post("/api/compensations").contentType("application/json").content("""
                {"tenantId":"t1","role":"ADMIN","requestId":"w8","idempotencyKey":"wk8",
                 "accountId":"wa1","entryId":"we1","eventRef":"WEVT1","compensationDelta":200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCode").value("COMPENSATION_ALREADY_APPLIED"));
        mockMvc.perform(get("/api/tenants/t1/accounts/wa1"))
                .andExpect(jsonPath("$.balance").value(500));
    }
}
