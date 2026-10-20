package com.obsidian.obsidian.vaults;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed default for {@link VaultBackup}: until a real implementation is wired, every
 * deletion job FAILS at the backup step and nothing is purged.
 * TODO(M7b merge): wire sync.DbBackupService's per-vault export as the VaultBackup bean.
 */
@Configuration
public class VaultBackupConfig {
    @Bean
    @ConditionalOnMissingBean(VaultBackup.class)
    VaultBackup unwiredVaultBackup() {
        return id -> { throw new IllegalStateException("no per-vault backup wired (VaultBackup); refusing to purge vault " + id); };
    }
}
