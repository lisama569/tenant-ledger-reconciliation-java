package com.github.lisama569.tenantledgerreconciliationjava.config;

import com.github.lisama569.tenantledgerreconciliationjava.storage.LedgerRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 装配本地存储仓库（启动重放由 ReplayService 负责）。 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfig {

    @Bean
    public LedgerRepository ledgerRepository(LedgerProperties properties) {
        // 骨架
        return new LedgerRepository(Path.of(properties.getStorageFile()));
    }
}
