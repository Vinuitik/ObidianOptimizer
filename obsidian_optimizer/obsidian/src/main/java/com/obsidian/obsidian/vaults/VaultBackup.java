package com.obsidian.obsidian.vaults;

/**
 * Port: write a restorable, per-vault backup of ONE vault's rows (all tables carrying
 * {@code vault_id}) before {@link VaultDeletionWorker} purges them. Must throw on any failure
 * (including "Drive/encryption not configured") so the purge never runs without a backup.
 * Implemented by {@code sync.DbBackupService} (Drive-only, encrypted).
 */
public interface VaultBackup {
    void backup(long vaultId) throws Exception;

    /** Null if {@link #backup} can run for this vault now, else a human-readable reason. Checked at
     *  enqueue time so deletion is refused up-front instead of failing later in the worker. */
    default String blockedReason(long vaultId) { return null; }
}
