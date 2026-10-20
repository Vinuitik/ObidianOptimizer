package com.obsidian.obsidian.vaults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable vault-deletion queue ({@code vault_deletion_jobs}, DDL in {@code VaultRepository.initSchema}) + the purge. */
@Repository
public class VaultDeletionRepository {

    private static final Logger log = LoggerFactory.getLogger(VaultDeletionRepository.class);

    private final JdbcTemplate jdbc;

    public VaultDeletionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Enqueue (409 active / primary / last remaining; 404 unknown). Already QUEUED/RUNNING = no-op; FAILED = requeued. */
    @Transactional
    public Map<String, Object> enqueue(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM vaults WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw VaultException.notFound(id);
        Map<String, Object> v = rows.get(0);
        if (Boolean.TRUE.equals(v.get("active"))) throw VaultException.conflict("can't delete the active vault");
        if (Boolean.TRUE.equals(v.get("is_primary"))) throw VaultException.conflict("can't delete the primary vault");
        Integer others = jdbc.queryForObject("""
            SELECT count(*) FROM vaults v WHERE v.id <> ?
              AND NOT EXISTS (SELECT 1 FROM vault_deletion_jobs j WHERE j.target_vault_id = v.id AND j.status <> 'FAILED')
            """, Integer.class, id);
        if (others == null || others == 0) throw VaultException.conflict("can't delete the last vault");
        jdbc.update("""
            INSERT INTO vault_deletion_jobs(target_vault_id, status) VALUES (?, 'QUEUED')
            ON CONFLICT (target_vault_id) DO UPDATE SET status = 'QUEUED', error = NULL, updated_at = now()
              WHERE vault_deletion_jobs.status = 'FAILED'
            """, id);
        return job(id);
    }

    public Map<String, Object> job(long id) {
        return jdbc.queryForMap("SELECT target_vault_id, status, backup_done, error FROM vault_deletion_jobs WHERE target_vault_id = ?", id);
    }

    public List<Long> claimPending(int limit) {
        return jdbc.queryForList("SELECT target_vault_id FROM vault_deletion_jobs WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT ?", Long.class, limit);
    }

    public void markRunning(long id) { setStatus(id, "RUNNING", null); }
    public void markDone(long id)    { setStatus(id, "DONE", null); }
    public void markFailed(long id, String error) { setStatus(id, "FAILED", error); }

    private void setStatus(long id, String status, String error) {
        jdbc.update("UPDATE vault_deletion_jobs SET status = ?, error = ?, updated_at = now() WHERE target_vault_id = ?", status, error, id);
    }

    public boolean backupDone(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT backup_done FROM vault_deletion_jobs WHERE target_vault_id = ?", Boolean.class, id));
    }

    public void markBackupDone(long id) {
        jdbc.update("UPDATE vault_deletion_jobs SET backup_done = true, updated_at = now() WHERE target_vault_id = ?", id);
    }

    /**
     * Deletes every row of {@code vaultId} from every base table with a {@code vault_id} column
     * (information_schema), then the registration. Each DELETE is its own statement, so it is
     * resumable and idempotent; FK-blocked tables are retried after their children went. DB rows only.
     */
    public void purge(long vaultId) {
        Map<String, Object> v = jdbc.queryForList("SELECT active, is_primary FROM vaults WHERE id = ?", vaultId)
            .stream().findFirst().orElse(null);
        if (v == null) return;   // registration already gone: resumed after the last step
        if (Boolean.TRUE.equals(v.get("active")) || Boolean.TRUE.equals(v.get("is_primary")))
            throw new IllegalStateException("vault " + vaultId + " became active/primary; refusing to purge");
        List<String> pending = new ArrayList<>(jdbc.queryForList("""
            SELECT c.table_name FROM information_schema.columns c
            JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.column_name = 'vault_id' AND c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
            ORDER BY c.table_name
            """, String.class));
        Exception last = null;
        while (!pending.isEmpty()) {
            List<String> blocked = new ArrayList<>();
            for (String t : pending) {
                try {
                    int n = jdbc.update("DELETE FROM \"" + t.replace("\"", "\"\"") + "\" WHERE vault_id = ?", vaultId);
                    if (n > 0) log.info("[VaultDeletion] vault {}: purged {} row(s) from {}", vaultId, n, t);
                } catch (DataIntegrityViolationException e) {
                    blocked.add(t); last = e;
                }
            }
            if (blocked.size() == pending.size())
                throw new IllegalStateException("purge stuck on FK for tables " + blocked, last);
            pending = blocked;
        }
        jdbc.update("DELETE FROM vaults WHERE id = ? AND NOT is_primary AND NOT active", vaultId);
    }
}
