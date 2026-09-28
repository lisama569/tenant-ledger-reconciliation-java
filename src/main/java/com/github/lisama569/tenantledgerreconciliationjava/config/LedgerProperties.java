package com.github.lisama569.tenantledgerreconciliationjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地账本可配置项。
 *
 * @param dataDir append-only 操作日志所在目录，默认 {@code ./ledger-data}
 */
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    private String dataDir = "ledger-data";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
