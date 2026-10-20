package com.obsidian.obsidian.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Per-vault logical snapshot of the DB: a zip of {@code manifest.json} + one
 * {@code data/<table>.copy} (Postgres COPY text format) per table.
 *
 * <p>Tables are DISCOVERED from the live catalog, never listed by hand:
 * <ul>
 *   <li>vault-scoped = has a {@code vault_id} column, or (no vault_id) has a single-column FK to a
 *       scoped table (attempts, track_items, ... — owned through their parent row);</li>
 *   <li>global = everything else (app_settings, bandit_arms, the vaults registry ...). Only the
 *       PRIMARY vault's snapshot carries globals; a restore of the primary replaces them, except the
 *       registry (tables FK-referenced by scoped tables) which is exported but never imported.</li>
 *   <li>device-local keys ({@link #DEVICE_LOCAL_VAULT_KEYS}/{@link #DEVICE_LOCAL_GLOBAL_KEYS}: Drive refresh
 *       token, passphrase, device id, ...) are kept from THIS device on restore, never taken from the backup.</li>
 * </ul>
 */
final class VaultDbSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(VaultDbSnapshotter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int FORMAT = 1;

    /** Per-device credentials/identity/paths in {@code vault_settings}: a restore NEVER overwrites this device's values. */
    static final List<String> DEVICE_LOCAL_VAULT_KEYS = List.of(
        "sync.refresh_token", "sync.account_email", "sync.device_id", "syncPassphrase",
        "sync.drive.folder_id", "resourcePath");
    /** Same for global {@code app_settings} (OAuth app creds, host path, legacy pre-per-vault copies). */
    static final List<String> DEVICE_LOCAL_GLOBAL_KEYS = List.of(
        "syncClientId", "syncClientSecret", "sync.oauth.redirect_uri", "vaultPath",
        "sync.refresh_token", "sync.account_email", "sync.device_id", "syncPassphrase", "sync.drive.folder_id");

    record Kept(long vaultId, String key, String value) {}   // vaultId 0 = app_settings

    private static String in(List<String> keys) { return "(" + String.join(",", Collections.nCopies(keys.size(), "?")) + ")"; }

    private List<Kept> keepVault(String vaultFilter, Object... args) {
        List<Object> a = new ArrayList<>(List.of(args)); a.addAll(DEVICE_LOCAL_VAULT_KEYS);
        return jdbc.query("SELECT vault_id, key, value FROM vault_settings WHERE " + vaultFilter + " key IN " + in(DEVICE_LOCAL_VAULT_KEYS),
            (rs, i) -> new Kept(rs.getLong(1), rs.getString(2), rs.getString(3)), a.toArray());
    }

    private List<Kept> keepGlobal() {
        return jdbc.query("SELECT key, value FROM app_settings WHERE key IN " + in(DEVICE_LOCAL_GLOBAL_KEYS),
            (rs, i) -> new Kept(0, rs.getString(1), rs.getString(2)), DEVICE_LOCAL_GLOBAL_KEYS.toArray());
    }

    /** Re-impose this device's values: drop whatever the restore brought for these keys, re-insert the kept ones
     *  (absent locally = absent after). {@code vaultIds} null = all vaults. */
    private void reimpose(List<Kept> kept, Collection<Long> vaultIds, boolean globals) {
        if (vaultIds == null) jdbc.update("DELETE FROM vault_settings WHERE key IN " + in(DEVICE_LOCAL_VAULT_KEYS), DEVICE_LOCAL_VAULT_KEYS.toArray());
        else for (long v : vaultIds) {
            List<Object> a = new ArrayList<>(); a.add(v); a.addAll(DEVICE_LOCAL_VAULT_KEYS);
            jdbc.update("DELETE FROM vault_settings WHERE vault_id = ? AND key IN " + in(DEVICE_LOCAL_VAULT_KEYS), a.toArray());
        }
        if (globals) jdbc.update("DELETE FROM app_settings WHERE key IN " + in(DEVICE_LOCAL_GLOBAL_KEYS), DEVICE_LOCAL_GLOBAL_KEYS.toArray());
        for (Kept k : kept) {
            if (k.vaultId() == 0) { if (globals) jdbc.update("INSERT INTO app_settings(key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", k.key(), k.value()); }
            else jdbc.update("INSERT INTO vault_settings(vault_id, key, value) SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM vaults WHERE id = ?) "
                + "ON CONFLICT (vault_id, key) DO UPDATE SET value = EXCLUDED.value", k.vaultId(), k.key(), k.value(), k.vaultId());
        }
    }

    /** Legacy whole-DB pg_restore: capture every device-local row first... */
    List<Kept> captureDeviceLocalAll() {
        List<Kept> all = new ArrayList<>(keepVault(""));
        all.addAll(keepGlobal());
        return all;
    }

    /** ...and put them back afterwards. */
    void reapplyDeviceLocalAll(List<Kept> kept) { reimpose(kept, null, true); }

    private final JdbcTemplate jdbc;

    VaultDbSnapshotter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Catalog discovery ────────────────────────────────────────────────────

    record Fk(String table, String column, String refTable, String refColumn) {}

    /** One backed-up table. {@code where} is null for global tables. */
    record Tbl(String name, List<String> columns, String where, boolean registry) {
        boolean global() { return where == null; }
    }

    private static String q(String ident) { return "\"" + ident.replace("\"", "\"\"") + "\""; }

    /** Scoped tables (parents before children) then globals, for the given vault. */
    List<Tbl> discover(long vaultId) {
        // User tables in public, excluding extension-owned ones (e.g. postgis spatial_ref_sys).
        List<String> tables = jdbc.queryForList("""
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r','p')
              AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
            ORDER BY c.relname""", String.class);
        Set<String> tableSet = new HashSet<>(tables);

        Map<String, List<String>> cols = new HashMap<>();
        jdbc.query("""
            SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'public' ORDER BY table_name, ordinal_position""",
            rs -> { cols.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); });

        List<Fk> fks = jdbc.query("""
            SELECT cl.relname, a.attname, fcl.relname, fa.attname
            FROM pg_constraint co
            JOIN pg_class cl  ON cl.oid  = co.conrelid
            JOIN pg_class fcl ON fcl.oid = co.confrelid
            JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
            JOIN pg_attribute a  ON a.attrelid  = co.conrelid  AND a.attnum  = co.conkey[1]
            JOIN pg_attribute fa ON fa.attrelid = co.confrelid AND fa.attnum = co.confkey[1]
            WHERE co.contype = 'f' AND array_length(co.conkey, 1) = 1""",
            (rs, i) -> new Fk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));

        // Resolve scoped predicates to a fixed point (handles child-of-child chains).
        Map<String, String> where = new LinkedHashMap<>();
        for (String t : tables) if (cols.getOrDefault(t, List.of()).contains("vault_id")) where.put(t, "vault_id = " + vaultId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String t : tables) {
                if (where.containsKey(t)) continue;
                List<String> parts = new ArrayList<>();
                for (Fk fk : fks) {
                    if (!fk.table().equals(t) || fk.refTable().equals(t) || !where.containsKey(fk.refTable())) continue;
                    parts.add(q(fk.column()) + " IN (SELECT " + q(fk.refColumn()) + " FROM " + q(fk.refTable())
                        + " WHERE " + where.get(fk.refTable()) + ")");
                }
                if (!parts.isEmpty()) { where.put(t, "(" + String.join(" OR ", parts) + ")"); changed = true; }
            }
        }

        // Registry = global table that a scoped table references (vaults).
        Set<String> registry = new HashSet<>();
        for (Fk fk : fks)
            if (where.containsKey(fk.table()) && !where.containsKey(fk.refTable()) && tableSet.contains(fk.refTable()))
                registry.add(fk.refTable());

        // Topological order of scoped tables: parents first.
        List<String> scoped = new ArrayList<>(where.keySet());
        Collections.sort(scoped);
        List<String> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (ordered.size() < scoped.size()) {
            boolean progress = false;
            for (String t : scoped) {
                if (done.contains(t)) continue;
                boolean ready = true;
                for (Fk fk : fks)
                    if (fk.table().equals(t) && !fk.refTable().equals(t) && where.containsKey(fk.refTable()) && !done.contains(fk.refTable())) { ready = false; break; }
                if (ready) { ordered.add(t); done.add(t); progress = true; }
            }
            if (!progress) { for (String t : scoped) if (done.add(t)) ordered.add(t); } // FK cycle: name order
        }

        List<Tbl> out = new ArrayList<>();
        for (String t : ordered) out.add(new Tbl(t, cols.get(t), where.get(t), false));
        for (String t : tables) if (!where.containsKey(t)) out.add(new Tbl(t, cols.get(t), null, registry.contains(t)));
        return out;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /** Snapshot of one vault's rows (+ globals when {@code includeGlobals}). One REPEATABLE READ tx. */
    byte[] export(long vaultId, boolean includeGlobals) {
        TransactionTemplate tx = txTemplate();
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        return tx.execute(st -> jdbc.execute((Connection con) -> {
            try {
                CopyManager cm = con.unwrap(PGConnection.class).getCopyAPI();
                List<Tbl> tbls = discover(vaultId);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                List<Map<String, Object>> manifestTables = new ArrayList<>();
                try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                    for (Tbl t : tbls) {
                        if (t.global() && !includeGlobals) continue;
                        String sel = "SELECT * FROM " + q(t.name()) + (t.global() ? "" : " WHERE " + t.where());
                        zip.putNextEntry(new ZipEntry("data/" + t.name() + ".copy"));
                        long n = cm.copyOut("COPY (" + sel + ") TO STDOUT", nonClosing(zip));
                        zip.closeEntry();
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", t.name()); m.put("columns", t.columns());
                        m.put("global", t.global()); m.put("registry", t.registry()); m.put("rows", n);
                        manifestTables.add(m);
                    }
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("format", FORMAT); manifest.put("vaultId", vaultId);
                    manifest.put("includesGlobals", includeGlobals); manifest.put("tables", manifestTables);
                    zip.putNextEntry(new ZipEntry("manifest.json"));
                    zip.write(JSON.writeValueAsBytes(manifest));
                    zip.closeEntry();
                }
                return bos.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("vault snapshot export failed: " + e.getMessage(), e);
            }
        }));
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * One transaction: delete the vault's rows from every scoped table (children first), COPY the
     * snapshot in (parents first), realign serial sequences. Any failure rolls everything back.
     * Globals are replaced only when the snapshot has them AND {@code vaultIsPrimary}; the registry
     * is never imported.
     */
    @SuppressWarnings("unchecked")
    void restore(long vaultId, boolean vaultIsPrimary, byte[] snapshot) {
        Map<String, byte[]> entries = readZip(snapshot);
        byte[] mf = entries.get("manifest.json");
        if (mf == null) throw new IllegalStateException("Backup is not a per-vault snapshot (no manifest)");
        Map<String, Object> manifest;
        try { manifest = JSON.readValue(mf, Map.class); }
        catch (IOException e) { throw new IllegalStateException("Corrupt backup manifest: " + e.getMessage(), e); }
        if (((Number) manifest.get("format")).intValue() != FORMAT)
            throw new IllegalStateException("Unsupported backup format " + manifest.get("format"));
        if (((Number) manifest.get("vaultId")).longValue() != vaultId)
            throw new IllegalStateException("Backup belongs to vault " + manifest.get("vaultId") + ", not " + vaultId);
        boolean withGlobals = Boolean.TRUE.equals(manifest.get("includesGlobals")) && vaultIsPrimary;

        Map<String, List<String>> backupCols = new HashMap<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) manifest.get("tables"))
            backupCols.put((String) t.get("name"), (List<String>) t.get("columns"));

        txTemplate().executeWithoutResult(st -> {
            Integer reg = jdbc.queryForObject("SELECT COUNT(*) FROM vaults WHERE id = ?", Integer.class, vaultId);
            if (reg == null || reg == 0) throw new IllegalStateException("Vault " + vaultId + " is not registered on this device — add it first");
            List<Tbl> tbls = discover(vaultId);
            final List<Kept> kept = new ArrayList<>(keepVault("vault_id = ? AND", vaultId));
            if (withGlobals) kept.addAll(keepGlobal());
            jdbc.execute((Connection con) -> {
                try {
                    CopyManager cm = con.unwrap(PGConnection.class).getCopyAPI();
                    List<Tbl> scoped = tbls.stream().filter(t -> !t.global()).toList();
                    List<Tbl> globals = withGlobals ? tbls.stream().filter(t -> t.global() && !t.registry()).toList() : List.of();

                    for (int i = scoped.size() - 1; i >= 0; i--)
                        jdbc.update("DELETE FROM " + q(scoped.get(i).name()) + " WHERE " + scoped.get(i).where());
                    for (Tbl t : globals) jdbc.update("DELETE FROM " + q(t.name()));

                    List<Tbl> load = new ArrayList<>(scoped);
                    load.addAll(globals);
                    for (Tbl t : load) {
                        byte[] data = entries.get("data/" + t.name() + ".copy");
                        List<String> bc = backupCols.get(t.name());
                        if (data == null || bc == null) { log.warn("[DbBackup] table {} not in backup — left empty", t.name()); continue; }
                        for (String c : bc) if (!t.columns().contains(c))
                            throw new IllegalStateException("Schema drift: backup column " + t.name() + "." + c
                                + " no longer exists — restore with a matching app version");
                        if (data.length == 0) continue;
                        String cl = String.join(", ", bc.stream().map(VaultDbSnapshotter::q).toList());
                        try (Reader r = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                            cm.copyIn("COPY " + q(t.name()) + " (" + cl + ") FROM STDIN", r);
                        }
                        realignSequences(t);
                    }
                    reimpose(kept, List.of(vaultId), withGlobals);
                    return null;
                } catch (IOException | java.sql.SQLException e) {
                    throw new IllegalStateException("vault snapshot restore failed: " + e.getMessage(), e);
                }
            });
        });
    }

    /** COPY writes explicit ids without touching serial sequences → bump them past MAX. */
    private void realignSequences(Tbl t) {
        for (String c : t.columns()) {
            String seq = jdbc.queryForObject("SELECT pg_get_serial_sequence(?, ?)", String.class, q(t.name()), c);
            if (seq == null) continue;
            jdbc.queryForObject("SELECT setval(?::regclass, GREATEST(nextval(?::regclass), (SELECT COALESCE(MAX("
                + q(c) + "), 0) + 1 FROM " + q(t.name()) + ")), false)", Long.class, seq, seq);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    private static OutputStream nonClosing(OutputStream os) {
        return new OutputStream() {
            @Override public void write(int b) throws IOException { os.write(b); }
            @Override public void write(byte[] b, int o, int l) throws IOException { os.write(b, o, l); }
        };
    }

    private static Map<String, byte[]> readZip(byte[] zip) {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) m.put(e.getName(), in.readAllBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("Corrupt backup archive: " + ex.getMessage(), ex);
        }
        return m;
    }
}
