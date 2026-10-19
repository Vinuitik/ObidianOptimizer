package com.obsidian.obsidian.vaults;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
