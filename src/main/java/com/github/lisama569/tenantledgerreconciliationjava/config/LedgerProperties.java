package com.github.lisama569.tenantledgerreconciliationjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地账本配置：存储文件路径。 */
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {
    /** 操作日志 JSON 文件路径。 */
    private String storageFile = "build/ledger-state.json";

    public String getStorageFile() {
        return storageFile;
    }

    public void setStorageFile(String storageFile) {
        this.storageFile = storageFile;
    }
}
