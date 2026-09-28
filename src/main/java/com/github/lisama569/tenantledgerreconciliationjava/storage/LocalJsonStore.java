package com.github.lisama569.tenantledgerreconciliationjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地 JSON 文件存储：
 * - 读：文件不存在则返回空状态；
 * - 写：先写临时文件再原子 move，避免中断产生半截文件。
 * 方法本身 synchronized；仓库层另有读写锁保证内存状态与落盘一致。
 */
public class LocalJsonStore {

    private final Path file;
    private final ObjectMapper mapper;

    public LocalJsonStore(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .findAndRegisterModules();
    }

    public synchronized LedgerState load() {
        if (!Files.exists(file)) {
            return new LedgerState();
        }
        try {
            return mapper.readValue(file.toFile(), LedgerState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取账本状态文件失败: " + file, e);
        }
    }

    public synchronized void save(LedgerState state) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入账本状态文件失败: " + file, e);
        }
    }
}
