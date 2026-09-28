package com.github.lisama569.tenantledgerreconciliationjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.tenantledgerreconciliationjava.storage.LocalLedgerStore;
import com.github.lisama569.tenantledgerreconciliationjava.storage.ReplayReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Bean 装配：创建唯一的 {@link LocalLedgerStore}（append-only 日志 + 内存态），
 * 并在应用启动时执行一次重放重建。重放报告作为 Bean 暴露，便于启动检查与测试断言。
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfig {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfig.class);

    @Bean
    public LocalLedgerStore localLedgerStore(LedgerProperties properties, ObjectMapper mapper) {
        LocalLedgerStore store = new LocalLedgerStore(Path.of(properties.getDataDir()), mapper);
        ReplayReport report = store.replay();
        log.info("ledger started dataDir={} applied={} rejected={} anomalies={}",
                properties.getDataDir(), report.getApplied(), report.getRejected(),
                report.hasAnomaly());
        report.getWarnings().forEach(w -> log.warn("startup replay warning: {}", w));
        return store;
    }

    @Bean
    public ReplayReport startupReplayReport(LocalLedgerStore store) {
        // localLedgerStore Bean 创建时已完成重放，直接复用报告，不触发第二次重放。
        return store.getLastReplayReport();
    }
}
