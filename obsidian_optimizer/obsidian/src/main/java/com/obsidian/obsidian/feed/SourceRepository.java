package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.vaults.VaultKeys;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Raw-JDBC repo for Passive Feed sources (youtube / rss / page_watch) — same convention as
 * {@code TrackRepository}: {@code @PostConstruct initSchema()} with CREATE TABLE IF NOT EXISTS.
 * See feed/FLOWS.md.
 */
@Repository
@DependsOn("vaultRepository")
public class SourceRepository {

    private final JdbcTemplate jdbc;

    public SourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code autoAccept} null = inherit the global {@code feedAutoAccept} setting. */
    public record Source(long id, String kind, String url, String feedUrl, String title,
                         Boolean autoAccept, String status,
                         Instant lastCheckedAt, Instant createdAt, int pendingCount) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sources (
              id              BIGSERIAL PRIMARY KEY,
              kind            TEXT NOT NULL,
              url             TEXT NOT NULL UNIQUE,
              feed_url        TEXT,
              title           TEXT,
              auto_accept     BOOLEAN,
              target_track_id BIGINT,   -- LEGACY: unused since I1 (accept never attaches to a track)
              last_checked_at TIMESTAMPTZ,
              created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
              status          TEXT NOT NULL DEFAULT 'active'
            )
            """);
        jdbc.execute("ALTER TABLE sources ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE sources ALTER COLUMN vault_id DROP DEFAULT");
        VaultKeys.swapUnique(jdbc, "sources", "url", "vault_id, url");
        // Bin (bin/FLOWS.md): subscription delete is soft; reads filter deleted_at IS NULL.
        jdbc.execute("ALTER TABLE sources ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ");
    }

    private static final String SELECT = """
        SELECT s.*, (SELECT COUNT(*) FROM feed_items fi WHERE fi.source_id = s.id AND fi.status = 'pending')
               AS pending_count
        FROM sources s
        """;

    public Source create(long vaultId, String kind, String url, String feedUrl, String title,
                         Boolean autoAccept) {
        // (vault_id, url) is UNIQUE: re-subscribing supersedes a binned copy of the same URL.
        jdbc.update("DELETE FROM sources WHERE vault_id = ? AND url = ? AND deleted_at IS NOT NULL", vaultId, url);
        long id = jdbc.queryForObject("""
            INSERT INTO sources(kind, url, feed_url, title, auto_accept, vault_id)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, kind, url, feedUrl, title, autoAccept, vaultId);
        return get(vaultId, id);
    }

    public Source get(long vaultId, long id) {
        List<Source> rows = jdbc.query(SELECT + " WHERE s.vault_id = ? AND s.id = ? AND s.deleted_at IS NULL", SourceRepository::map, vaultId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Source getByUrl(long vaultId, String url) {
        List<Source> rows = jdbc.query(SELECT + " WHERE s.vault_id = ? AND s.url = ? AND s.deleted_at IS NULL", SourceRepository::map, vaultId, url);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Source> list(long vaultId) {
        return jdbc.query(SELECT + " WHERE s.vault_id = ? AND s.deleted_at IS NULL ORDER BY s.created_at DESC, s.id DESC", SourceRepository::map, vaultId);
    }

    public List<Source> listActive(long vaultId) {
        return jdbc.query(SELECT + " WHERE s.vault_id = ? AND s.status = 'active' AND s.deleted_at IS NULL ORDER BY s.id", SourceRepository::map, vaultId);
    }

    /** Partial update — null fields unchanged. {@code clearAutoAccept}
     *  resets to "inherit" (the PATCH body sent an explicit null). */
    public Source update(long vaultId, long id, Boolean autoAccept, boolean clearAutoAccept,
                         String status, String title) {
        if (clearAutoAccept) jdbc.update("UPDATE sources SET auto_accept = NULL WHERE id = ?", id);
        else if (autoAccept != null) jdbc.update("UPDATE sources SET auto_accept = ? WHERE id = ?", autoAccept, id);
        if (status != null) jdbc.update("UPDATE sources SET status = ? WHERE id = ?", status, id);
        if (title != null) jdbc.update("UPDATE sources SET title = ? WHERE id = ?", title, id);
        return get(vaultId, id);
    }

    /** Poll cursor — advances even for a valid-but-empty feed. */
    public void markChecked(long id, Instant when) {
        jdbc.update("UPDATE sources SET last_checked_at = ? WHERE id = ?", Timestamp.from(when), id);
    }

    public void updateResolved(long id, String kind, String feedUrl) {
        jdbc.update("UPDATE sources SET kind = ?, feed_url = ? WHERE id = ?", kind, feedUrl, id);
    }

    /** Soft delete (Bin): the source's feed items vanish from every read because FeedRepository joins
     *  sources on deleted_at IS NULL. Already-ingested notes are untouched. */
    public boolean delete(long vaultId, long id) {
        return jdbc.update("UPDATE sources SET deleted_at = now() WHERE id = ? AND vault_id = ? AND deleted_at IS NULL",
            id, vaultId) > 0;
    }

    // ── Bin support (bin/BinService) ─────────────────────────────────────────

    public record BinSource(long id, String title, String url, Instant deletedAt) {}

    public List<BinSource> listDeleted(long vaultId) {
        return jdbc.query("SELECT id, title, url, deleted_at FROM sources WHERE vault_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC",
            (rs, n) -> new BinSource(rs.getLong("id"), rs.getString("title"), rs.getString("url"),
                rs.getTimestamp("deleted_at").toInstant()), vaultId);
    }

    /** Restore: false if not in the bin; throws IllegalStateException if the URL was re-subscribed meanwhile. */
    public boolean restore(long vaultId, long id) {
        List<String> urls = jdbc.queryForList("SELECT url FROM sources WHERE id = ? AND vault_id = ? AND deleted_at IS NOT NULL",
            String.class, id, vaultId);
        if (urls.isEmpty()) return false;
        if (getByUrl(vaultId, urls.get(0)) != null) throw new IllegalStateException("already subscribed to " + urls.get(0));
        return jdbc.update("UPDATE sources SET deleted_at = NULL WHERE id = ?", id) > 0;
    }

    /** Forever: hard delete (cascades feed_items). */
    public boolean purge(long vaultId, long id) {
        return jdbc.update("DELETE FROM sources WHERE id = ? AND vault_id = ? AND deleted_at IS NOT NULL", id, vaultId) > 0;
    }

    public int purgeDeletedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM sources WHERE deleted_at IS NOT NULL AND deleted_at < ?", Timestamp.from(cutoff));
    }

    private static Source map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp checked = rs.getTimestamp("last_checked_at");
        Boolean auto = (Boolean) rs.getObject("auto_accept");
        return new Source(rs.getLong("id"), rs.getString("kind"), rs.getString("url"),
            rs.getString("feed_url"), rs.getString("title"), auto,
            rs.getString("status"),
            checked == null ? null : checked.toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getInt("pending_count"));
    }
}
