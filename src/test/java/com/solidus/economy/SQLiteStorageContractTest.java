package com.solidus.economy;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Binds the {@link StorageBackendContractTest} harness to the default
 * {@link SQLiteStorage} backend over a temporary isolated database file.
 * Always runs - this is the CI baseline for the storage contract.
 */
public class SQLiteStorageContractTest extends StorageBackendContractTest {

    private Path tempDir;

    @Override
    protected StorageBackend createBackend() throws Exception {
        tempDir = Files.createTempDirectory("solidus-contract-sqlite");
        return new SQLiteStorage(tempDir.toAbsolutePath().toString());
    }

    @Override
    protected void destroyBackend() throws Exception {
        if (tempDir != null) {
            try (var files = Files.list(tempDir)) {
                for (Path p : files.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(tempDir);
        }
    }
}
