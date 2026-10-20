package com.obsidian.obsidian.vaults;

/**
 * Port: write a restorable, per-vault backup of ONE vault's rows (all tables carrying
 * {@code vault_id}) before {@link VaultDeletionWorker} purges them. Must throw on any failure
 * so the purge never runs without a backup.
 *
 * <p>TODO(M7b merge): implement this on {@code sync.DbBackupService} (per-vault COPY ... WHERE
 * vault_id=?) and delete {@link VaultBackupConfig}'s fail-closed default.
 */
@FunctionalInterface
public interface VaultBackup {
    void backup(long vaultId) throws Exception;
}
