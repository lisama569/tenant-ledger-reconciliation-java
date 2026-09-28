package com.github.lisama569.tenantledgerreconciliationjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.github.lisama569.tenantledgerreconciliationjava.service.ReplayService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 容器冒烟：验证全部 Bean 可装配、启动重放成功执行且账本健康。
 * 容器测试使用独立存储文件（见 application-test 约定：默认 build/ledger-state.json，
 * 这里仅校验启动健康位，不与纯 JUnit 测试的临时文件相互干扰）。
 */
@SpringBootTest(properties = "ledger.storage-file=build/ledger-smoke.json")
public class ApplicationTests {

    @Autowired
    private ReplayService replayService;

    @Test
    public void contextLoadsAndStartupReplayHealthy() {
        assertNotNull(replayService);
        assertNotNull(replayService.lastReport(), "启动时必须自动执行一次重放");
        assertTrue(replayService.isHealthy());
    }
}
