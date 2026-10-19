package com.obsidian.obsidian.vaults;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent "add vault_id to this key" DDL for the per-repo initSchema() methods (M2).
 * Each call is one DO block = one transaction; it is a no-op once the key already
 * contains vault_id. Table/column names are code constants, never user input.
 */
public final class VaultKeys {
    private VaultKeys() {}

    /** PRIMARY KEY (cols) -> PRIMARY KEY (cols, vault_id). Referencing FKs would block the drop
     *  (none exist on these tables: every FK targets a surrogate id). */
    public static void swapPrimaryKey(JdbcTemplate jdbc, String table, String cols) {
        jdbc.execute("""
            DO $$ DECLARE c text; BEGIN
              SELECT conname INTO c FROM pg_constraint
               WHERE conrelid = '%1$s'::regclass AND contype = 'p'
                 AND pg_get_constraintdef(oid) NOT LIKE '%%vault_id%%';
              IF c IS NOT NULL THEN
                EXECUTE format('ALTER TABLE %1$s DROP CONSTRAINT %%I', c);
                EXECUTE 'ALTER TABLE %1$s ADD PRIMARY KEY (%2$s, vault_id)';
              END IF;
            END $$
            """.formatted(table, cols));
    }

    /** UNIQUE (oldCols) constraint -> UNIQUE (newCols), same constraint name. */
    public static void swapUnique(JdbcTemplate jdbc, String table, String oldCols, String newCols) {
        jdbc.execute("""
            DO $$ DECLARE c text; BEGIN
              SELECT conname INTO c FROM pg_constraint
               WHERE conrelid = '%1$s'::regclass AND contype = 'u'
                 AND pg_get_constraintdef(oid) = 'UNIQUE (%2$s)';
              IF c IS NOT NULL THEN
                EXECUTE format('ALTER TABLE %1$s DROP CONSTRAINT %%I', c);
                EXECUTE format('ALTER TABLE %1$s ADD CONSTRAINT %%I UNIQUE (%3$s)', c);
              END IF;
            END $$
            """.formatted(table, oldCols, newCols));
    }

    /** Named unique INDEX -> same name over newCols (only if its definition lacks vault_id). */
    public static void swapUniqueIndex(JdbcTemplate jdbc, String table, String index, String newCols) {
        jdbc.execute("""
            DO $$ BEGIN
              IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema()
                           AND indexname = '%2$s' AND indexdef NOT LIKE '%%vault_id%%') THEN
                DROP INDEX %2$s;
                CREATE UNIQUE INDEX %2$s ON %1$s(%3$s);
              END IF;
            END $$
            """.formatted(table, index, newCols));
    }
}
