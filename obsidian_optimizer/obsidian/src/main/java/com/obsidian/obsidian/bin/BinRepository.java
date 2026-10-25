package com.obsidian.obsidian.bin;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Raw-JDBC repo for {@code bin_entries}: one row per file-ish thing moved into the vault's
 * {@code _trash/} (note | folder | file). Tracks/subscriptions/feed items live in their own tables
 * (deleted_at / status), not here. Paths are stored vault-root-relative. See bin/FLOWS.md.
 */
@Repository
@DependsOn("vaultRepository")
public class BinRepository {

    private final JdbcTemplate jdbc;

    public BinRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Entry(long id, long vaultId, String kind, String trashPath, String originalPath,
                        String label, Instant deletedAt, Long parentId) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS bin_entries (
              id            BIGSERIAL PRIMARY KEY,
              vault_id      BIGINT NOT NULL REFERENCES vaults(id),
              kind          TEXT NOT NULL,
              trash_path    TEXT NOT NULL,
              original_path TEXT NOT NULL,
              label         TEXT NOT NULL,
              deleted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
              parent_id     BIGINT
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bin_entries_vault ON bin_entries(vault_id, deleted_at DESC)");
    }

    public long record(long vaultId, String kind, String trashPath, String originalPath, String label) {
        return jdbc.queryForObject("""
            INSERT INTO bin_entries(vault_id, kind, trash_path, original_path, label)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, vaultId, kind, trashPath, originalPath, label);
    }

    public List<Entry> list(long vaultId) {
        return jdbc.query("SELECT * FROM bin_entries WHERE vault_id = ? ORDER BY deleted_at DESC, id DESC",
            BinRepository::map, vaultId);
    }

    public Entry get(long vaultId, long id) {
        List<Entry> rows = jdbc.query("SELECT * FROM bin_entries WHERE vault_id = ? AND id = ?",
            BinRepository::map, vaultId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Entry> listBefore(Instant cutoff) {
        return jdbc.query("SELECT * FROM bin_entries WHERE deleted_at < ?", BinRepository::map, Timestamp.from(cutoff));
    }

    public void delete(long id) { jdbc.update("DELETE FROM bin_entries WHERE id = ?", id); }

    private static Entry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Entry(rs.getLong("id"), rs.getLong("vault_id"), rs.getString("kind"),
            rs.getString("trash_path"), rs.getString("original_path"), rs.getString("label"),
            rs.getTimestamp("deleted_at").toInstant(), (Long) rs.getObject("parent_id"));
    }
}
