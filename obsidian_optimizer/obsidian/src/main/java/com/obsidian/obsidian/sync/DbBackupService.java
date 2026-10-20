package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.sync.DriveService.DbBackupInfo;
import com.obsidian.obsidian.vaults.VaultBackup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backs up the Postgres database (all derived intelligence — embeddings, cards, image
 * OCR, review state) to Google Drive as an encrypted PER-VAULT snapshot (see VaultDbSnapshotter: only that vault's
 * rows; globals only for the primary vault), and restores it on a fresh
 * device. Vault FILES are handled by the per-file sync; this covers the expensive-to-
 * regenerate DB so a device move doesn't re-run the whole embed/caption/card pipeline.
 *
 * <p>Legacy whole-DB pg_dump files (.pgdump.enc) can still be restored via pg_restore. Dumps live in Drive's {@code _db/} folder, which the
 * janitor and file-sync deliberately skip.
 */
@Service
public class DbBackupService implements VaultBackup {

    private static final Logger log = LoggerFactory.getLogger(DbBackupService.class);
    /** Per-vault snapshot (zip of COPY files); anything else ending .enc is a legacy whole-DB pg_dump. */
    static final String SNAPSHOT_SUFFIX = ".vdb.enc";
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DriveService            driveService;
    private final VaultEncryptionService  encryptionService;
    private final SyncService             syncService;
    private final DeviceIdentityService   deviceIdentityService;
    private final JdbcTemplate            jdbc;
    private final VaultDbSnapshotter      snapshotter;

    @Value("${spring.datasource.url}")      private String jdbcUrl;
    @Value("${spring.datasource.username}") private String dbUser;
    @Value("${spring.datasource.password}") private String dbPassword;

    /** Dumps to keep in _db/; older ones are hard-deleted after each backup. */
    @Value("${sync.dbbackup.keep:3}")       private int keep;

    // Live progress for GET /sync/status (one op at a time via the sync lane).
    private volatile boolean backupRunning = false;
    private volatile boolean restoring     = false;
    private volatile String  restorePhase  = "";

    public DbBackupService(DriveService driveService,
                           VaultEncryptionService encryptionService,
                           SyncService syncService,
                           DeviceIdentityService deviceIdentityService,
                           JdbcTemplate jdbc) {
        this.driveService          = driveService;
        this.encryptionService     = encryptionService;
        this.syncService           = syncService;
        this.deviceIdentityService = deviceIdentityService;
        this.jdbc                  = jdbc;
        this.snapshotter           = new VaultDbSnapshotter(jdbc);
    }

    // ── Backup ──────────────────────────────────────────────────────────────────

    /** per-vault COPY snapshot → encrypt → upload to _db/ → prune to `keep`. Best-effort file drain first
     *  so the dump ≈ what's on Drive. Meant to run on the sync lane (single-flight). */
    public void backupNow(long vaultId) {
        String blocked = blockedReason(vaultId);
        if (blocked != null) { log.warn("[DbBackup] {} — skipping", blocked); return; }
        doBackup(vaultId);
    }

    /** {@link VaultBackup} port (pre-delete backup): same snapshot, but never skips — throws when
     *  Drive/encryption is missing or the upload fails, so the deletion worker purges nothing. */
    @Override
    public void backup(long vaultId) {
        String blocked = blockedReason(vaultId);
        if (blocked != null) throw new IllegalStateException(blocked);
        doBackup(vaultId);
    }

    /** Null if a Drive+encrypted backup can run for this vault. */
    @Override
    public String blockedReason(long vaultId) {
        if (!encryptionService.isConfigured(vaultId)) return "encryption passphrase not configured for vault " + vaultId;
        if (!driveService.isConfigured(vaultId))      return "Google Drive not configured for vault " + vaultId;
        return null;
    }

    private void doBackup(long vaultId) {
        backupRunning = true;
        try {
            // Note: we deliberately DON'T drain the file queue here — that's the upload
            // cron's job (every 6h) and coupling it would make each backup a full sync.
            // The dump snapshots current DB; for a perfectly-consistent snapshot, Sync first.
            boolean primary = isPrimary(vaultId);
            log.info("[DbBackup] vault {} snapshot starting (globals={})", vaultId, primary);
            byte[] snapshot = snapshotter.export(vaultId, primary);

            byte[] enc = encryptionService.encrypt(vaultId, snapshot);
            String name = "obsidian-db-v" + vaultId + "-" + TS.format(ZonedDateTime.now(ZoneOffset.UTC)) + SNAPSHOT_SUFFIX;
            String id = driveService.uploadDbBackup(vaultId, enc, name, serverVersion(), deviceIdentityService.getDeviceId(vaultId));
            log.info("[DbBackup] uploaded {} ({} bytes encrypted, driveId={})", name, enc.length, id);

            prune(vaultId);
        } catch (Exception e) {
            log.error("[DbBackup] backup failed: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            backupRunning = false;
        }
    }

    /** Keep only the newest `keep` dumps; hard-delete the rest. */
    private void prune(long vaultId) throws IOException {
        List<DbBackupInfo> all = driveService.listDbBackups(vaultId); // newest first
        for (int i = keep; i < all.size(); i++) {
            try {
                driveService.deleteDbBackup(vaultId, all.get(i).fileId());
                log.info("[DbBackup] pruned old dump {}", all.get(i).name());
            } catch (Exception e) {
                log.warn("[DbBackup] prune failed for {}: {}", all.get(i).name(), e.getMessage());
            }
        }
    }

    // ── Restore ─────────────────────────────────────────────────────────────────

    /**
     * Download the latest dump → decrypt → restore (device-local credentials/identity kept) → materialize vault files quietly.
     * Guarded: refuses on a non-empty DB unless {@code force} (restore is destructive —
     * pg_restore --clean drops existing objects). Meant to run on the sync lane.
     */
    public void restore(long vaultId, boolean force) {
        if (!encryptionService.isConfigured(vaultId)) throw new IllegalStateException("Encryption passphrase not set — set it before restoring");
        if (!driveService.isConfigured(vaultId))      throw new IllegalStateException("Google Drive not connected");
        if (!force && !isDbEmpty(vaultId))     throw new IllegalStateException("Database is not empty — pass force=true to overwrite");

        restoring = true;
        Path tmp = null;
        try {
            restorePhase = "locating backup";
            Optional<DbBackupInfo> latest = driveService.latestDbBackup(vaultId);
            if (latest.isEmpty()) throw new IllegalStateException("No DB backup found in Drive _db/");
            DbBackupInfo backup = latest.get();

            restorePhase = "downloading";
            byte[] enc = driveService.downloadFile(vaultId, backup.fileId());
            byte[] dump = encryptionService.decrypt(vaultId, enc);
            if (!backup.name().endsWith(SNAPSHOT_SUFFIX)) {
                tmp = Files.createTempFile("obsidian-restore", ".pgdump");
                Files.write(tmp, dump);
            }
            log.info("[DbBackup] restoring from {} ({} bytes)", backup.name(), dump.length);

            if (backup.name().endsWith(SNAPSHOT_SUFFIX)) {
                restorePhase = "restoring vault rows";
                snapshotter.restore(vaultId, isPrimary(vaultId), dump);
            } else {
                // Legacy whole-DB pg_dump (pre per-vault backups). Overwrites ALL vaults — kept only so
                // old backups stay restorable on a fresh device.
                log.warn("[DbBackup] {} is a legacy whole-DB dump — restoring ALL vaults", backup.name());
                restorePhase = "pg_restore (legacy)";
                Db db = parseJdbc(jdbcUrl);
                var deviceLocal = snapshotter.captureDeviceLocalAll();   // pg_restore --clean would clobber these
                runPg(List.of("pg_restore", "--clean", "--if-exists", "--no-owner",
                              "-h", db.host, "-p", db.port, "-U", dbUser, "-d", db.name,
                              tmp.toString()), "pg_restore");
                snapshotter.reapplyDeviceLocalAll(deviceLocal);
            }

            restorePhase = "materializing vault files";
            encryptionService.reload(vaultId);               // passphrase may live in restored settings
            int files = syncService.downloadAllQuiet(vaultId);
            log.info("[DbBackup] restore complete — {} vault file(s) materialized. Restart recommended.", files);
            restorePhase = "done — restart recommended";
        } catch (Exception e) {
            log.error("[DbBackup] restore failed: {}", e.getMessage());
            restorePhase = "failed: " + e.getMessage();
            throw new RuntimeException(e);
        } finally {
            deleteQuietly(tmp);
            restoring = false;
        }
    }

    /** Null if a restore may proceed now, else a human-readable reason (for a synchronous 400). */
    public String restoreBlockedReason(long vaultId, boolean force) {
        if (!encryptionService.isConfigured(vaultId)) return "Encryption passphrase not set — set it first";
        if (!driveService.isConfigured(vaultId))      return "Google Drive not connected";
        if (!force && !isDbEmpty(vaultId))     return "Database is not empty — confirm to overwrite";
        try {
            if (driveService.latestDbBackup(vaultId).isEmpty()) return "No DB backup found in Drive";
        } catch (Exception e) {
            return "Could not reach Drive: " + e.getMessage();
        }
        return null;
    }

    // ── Status ──────────────────────────────────────────────────────────────────

    /** In-memory-only subset (no Drive calls) for the cheap GET /sync/progress poll. */
    public Map<String, Object> progressFragment() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dbBackupRunning", backupRunning);
        m.put("dbRestoring",     restoring);
        m.put("dbRestorePhase",  restorePhase);
        return m;
    }

    /** Fields merged into GET /sync/status. */
    public Map<String, Object> statusFragment(long vaultId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dbEmpty",       isDbEmpty(vaultId));
        m.put("dbBackupRunning", backupRunning);
        m.put("dbRestoring",   restoring);
        m.put("dbRestorePhase", restorePhase);
        Map<String, Object> backup = new LinkedHashMap<>();
        try {
            List<DbBackupInfo> all = driveService.isConfigured(vaultId) ? driveService.listDbBackups(vaultId) : List.of();
            backup.put("exists", !all.isEmpty());
            backup.put("count",  all.size());
            if (!all.isEmpty()) {
                backup.put("lastBackupAt", all.get(0).createdAt());
                backup.put("sizeBytes",    all.get(0).sizeBytes());
            }
        } catch (Exception e) {
            backup.put("exists", false);
            backup.put("error", e.getMessage());
        }
        m.put("dbBackup", backup);
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True when this vault has no notes yet (other vaults' rows don't block a per-vault restore). */
    private boolean isDbEmpty(long vaultId) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE vault_id = ?", Integer.class, vaultId);
            return n == null || n == 0;
        } catch (Exception e) {
            return true; // table absent → fresh DB
        }
    }

    private boolean isPrimary(long vaultId) {
        try {
            Boolean b = jdbc.queryForObject("SELECT is_primary FROM vaults WHERE id = ?", Boolean.class, vaultId);
            return Boolean.TRUE.equals(b);
        } catch (Exception e) {
            return false; // unknown vault → never ship globals
        }
    }

    private String serverVersion() {
        try { return jdbc.queryForObject("SHOW server_version", String.class); }
        catch (Exception e) { return ""; }
    }

    private void runPg(List<String> cmd, String label) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(label + " exited " + code + ": " + out.strip());
        }
        if (!out.isBlank()) log.info("[DbBackup] {} output: {}", label, out.strip());
    }

    /** jdbc:postgresql://host:port/db?params → (host, port, db). */
    static Db parseJdbc(String url) {
        String s = url.replaceFirst("^jdbc:postgresql://", "");
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = s.indexOf('/');
        String hostPort = slash >= 0 ? s.substring(0, slash) : s;
        String name     = slash >= 0 ? s.substring(slash + 1) : "postgres";
        int colon = hostPort.indexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        String port = colon >= 0 ? hostPort.substring(colon + 1) : "5432";
        return new Db(host, port, name);
    }

    record Db(String host, String port, String name) {}

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
