package com.obsidian.obsidian.vaults;

/** Published by {@link ActiveVault#switchTo} after the DB flag and in-memory state flipped.
 *  Scanners listen and rerun for {@code newVaultId}. */
public record VaultSwitchedEvent(long oldVaultId, long newVaultId) {}
