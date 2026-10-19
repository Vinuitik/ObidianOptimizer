package com.obsidian.obsidian.vaults;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Vault registry. Owns the {@code vaults} table; every vault_id FK points here. */
@Repository
public class VaultRepository {

    private final JdbcTemplate jdbc;
    private final String hostVaultPath;

    public VaultRepository(JdbcTemplate jdbc, @Value("${HOST_VAULT_PATH:}") String hostVaultPath) {
        this.jdbc = jdbc;
        this.hostVaultPath = hostVaultPath;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS vaults (
              id             BIGSERIAL PRIMARY KEY,
              name           TEXT NOT NULL,
              host_path      TEXT,
              container_path TEXT UNIQUE NOT NULL,
              active         BOOLEAN NOT NULL DEFAULT false,
              is_primary     BOOLEAN NOT NULL DEFAULT false,
              archived_at    TIMESTAMPTZ,
              created_at     TIMESTAMPTZ DEFAULT now()
            )
            """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_vaults_single_active ON vaults(active) WHERE active");
        String host = hostVaultPath == null || hostVaultPath.isBlank() ? null : hostVaultPath;
        jdbc.update("""
            INSERT INTO vaults(id, name, host_path, container_path, active, is_primary)
            VALUES (1, ?, ?, '/vault', true, true)
            ON CONFLICT DO NOTHING
            """, deriveName(host), host);
        jdbc.execute("SELECT setval(pg_get_serial_sequence('vaults','id'), "
            + "GREATEST((SELECT max(id) FROM vaults), 1))");
    }

    static String deriveName(String host) {
        if (host == null) return "NewLife";
        String p = host.replaceAll("/+$", "");
        String base = p.substring(p.lastIndexOf('/') + 1);
        return base.isBlank() ? "NewLife" : base;
    }

    public Long findActiveId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM vaults WHERE active", Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Map<String, Object> get(long id) {
        return jdbc.queryForMap("SELECT * FROM vaults WHERE id = ?", id);
    }

    public String containerPath(long id) {
        return jdbc.queryForObject("SELECT container_path FROM vaults WHERE id = ?", String.class, id);
    }

    private static final String VIEW_COLS =
        "id, name, host_path, container_path, active, is_primary, archived_at, created_at";

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("SELECT " + VIEW_COLS + " FROM vaults ORDER BY id");
    }

    public Map<String, Object> getView(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + VIEW_COLS + " FROM vaults WHERE id = ?", id);
        if (rows.isEmpty()) throw VaultException.notFound(id);
        return rows.get(0);
    }

    private Map<String, Object> require(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM vaults WHERE id = ?", id);
        if (rows.isEmpty()) throw VaultException.notFound(id);
        return rows.get(0);
    }

    /** Moves the single active flag to {@code id} in one transaction. 404 unknown, 409 archived. */
    @Transactional
    public void activate(long id) {
        Map<String, Object> v = require(id);
        if (v.get("archived_at") != null) throw VaultException.conflict("vault " + id + " is archived");
        jdbc.update("UPDATE vaults SET active = false WHERE active AND id <> ?", id);
        jdbc.update("UPDATE vaults SET active = true WHERE id = ?", id);
    }

    public void rename(long id, String name) {
        require(id);
        jdbc.update("UPDATE vaults SET name = ? WHERE id = ?", name, id);
    }

    /** Soft-hide. The active vault and the last non-archived vault can't be archived (409). */
    @Transactional
    public void archive(long id) {
        Map<String, Object> v = require(id);
        if (v.get("archived_at") != null) return;
        if (Boolean.TRUE.equals(v.get("active"))) throw VaultException.conflict("can't archive the active vault");
        Integer others = jdbc.queryForObject(
            "SELECT count(*) FROM vaults WHERE id <> ? AND archived_at IS NULL", Integer.class, id);
        if (others == null || others == 0) throw VaultException.conflict("can't archive the last vault");
        jdbc.update("UPDATE vaults SET archived_at = now() WHERE id = ?", id);
    }

    public void restore(long id) {
        require(id);
        jdbc.update("UPDATE vaults SET archived_at = NULL WHERE id = ?", id);
    }
}
