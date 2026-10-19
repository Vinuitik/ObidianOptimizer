package com.obsidian.obsidian.settings;

import com.obsidian.obsidian.vaults.VaultRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Global prefs live in {@code app_settings}; the keys in {@link #PER_VAULT_KEYS} live in
 * {@code vault_settings(vault_id, key)} and every accessor for them takes a {@code vaultId}.
 * Google OAuth app credentials stay global.
 */
@Component
@DependsOn("vaultRepository")
public class SettingsRepository {

    /** Keys moved from app_settings to vault_settings (copied to vault 1 at boot; the
     *  app_settings rows are left in place but never read again). */
    public static final List<String> PER_VAULT_KEYS = List.of(
        "syncEnabled", "sync.drive.folder_id", "sync.refresh_token", "sync.account_email",
        "syncPassphrase", "sync.device_id", "chronoLastRunDate", "resourcePath", "startupSyncMode");

    /** Thrown when a Drive folder id is already bound to another vault. */
    public static class DuplicateDriveFolderException extends RuntimeException {
        public DuplicateDriveFolderException(String folderId, long otherVaultId) {
            super("Drive folder " + folderId + " is already used by vault " + otherVaultId);
        }
    }

    private final JdbcTemplate jdbc;
    private final VaultRepository vaults;

    @Value("${VAULT_PATH:C:/Users/ACER/Desktop/NewLife}")
    private String defaultVaultPath;

    @Value("${IMAGE_PATH:#{null}}")
    private String defaultImagePath;

    // Hybrid-review daily caps. Env seeds fresh installs only (DB wins after — see
    // insertDefault). maxDailyReviews doubles as the chrono spread target (SpreadService).
    @Value("${REVIEW_MAX_DAILY:50}")
    private String defaultMaxDailyReviews;

    @Value("${REVIEW_MAX_DAILY_FLASHCARDS:20}")
    private String defaultMaxDailyFlashcards;

    // Sync seeds — env vars remain the headless bootstrap; the Settings UI owns the
    // values afterwards (DB wins over env once seeded).
    @Value("${SYNC_PASSPHRASE:}")
    private String defaultSyncPassphrase;

    @Value("${GOOGLE_DRIVE_FOLDER_ID:placeholder}")
    private String defaultDriveFolderId;

    @Value("${GOOGLE_OAUTH_CLIENT_ID:}")
    private String defaultOAuthClientId;

    @Value("${GOOGLE_OAUTH_CLIENT_SECRET:}")
    private String defaultOAuthClientSecret;

    @Value("${GOOGLE_OAUTH_REDIRECT_URI:}")
    private String defaultOAuthRedirectUri;

    public SettingsRepository(JdbcTemplate jdbc, VaultRepository vaults) {
        this.jdbc = jdbc;
        this.vaults = vaults;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS app_settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """);

        String imagePath = defaultImagePath != null
                ? defaultImagePath
                : defaultVaultPath + "/resources/images";

        insertDefault("vaultPath", defaultVaultPath);
        insertDefault("reviewPageSize", "20");
        insertDefault("maxDailyReviews", defaultMaxDailyReviews);
        insertDefault("maxDailyFlashcards", defaultMaxDailyFlashcards);
        insertDefault("bankruptcyLimit", "200");
        insertDefault("ollamaEmbedModel", "mixedbread-ai/mxbai-embed-large-v1");
        insertDefault("flashcardsEnabled", "true");
        insertDefault("chronicNeglectDays", "7");
        // Learning Tracks — default ON (finished work ships live, not behind a flag).
        insertDefault("tracksEnabled", "true");
        insertDefault("subscriptionsPollIntervalMs", "21600000"); // 6h
        // Passive Feed: false = swipe to triage; true = auto-accept NEW items (see feed/FLOWS.md).
        insertDefault("feedAutoAccept", "false");

        // Global Google OAuth app credentials (the per-vault sync keys are seeded below).
        seedIfBlank("syncClientId", defaultOAuthClientId);
        seedIfBlank("syncClientSecret", defaultOAuthClientSecret);
        seedIfBlank("sync.oauth.redirect_uri", defaultOAuthRedirectUri);

        // ── per-vault settings ────────────────────────────────────────────────
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS vault_settings (
                vault_id BIGINT NOT NULL REFERENCES vaults(id),
                key      TEXT   NOT NULL,
                value    TEXT   NOT NULL,
                PRIMARY KEY (vault_id, key)
            )
            """);
        // Idempotent: copies the legacy global rows to vault 1 only where vault 1 has no
        // value yet, so re-running never overwrites a later per-vault edit.
        jdbc.update(
            "INSERT INTO vault_settings(vault_id, key, value) "
            + "SELECT 1, key, value FROM app_settings WHERE key = ANY (string_to_array(?, ',')) "
            + "ON CONFLICT DO NOTHING",
            String.join(",", PER_VAULT_KEYS));
        // Fresh-install defaults for vault 1 (existing rows win).
        insertVaultDefault(1, "resourcePath", imagePath);
        insertVaultDefault(1, "startupSyncMode", "blocking");
        insertVaultDefault(1, "chronoLastRunDate", "");
        insertVaultDefault(1, "sync.device_id", "");
        insertVaultDefault(1, "syncEnabled", "true");
        insertVaultDefault(1, "sync.refresh_token", "");
        insertVaultDefault(1, "sync.account_email", "");
        // Env-backed: fill only while blank, so a var added to .env later still lands.
        seedVaultIfBlank(1, "syncPassphrase", defaultSyncPassphrase);
        seedVaultIfBlank(1, "sync.drive.folder_id", isPlaceholder(defaultDriveFolderId) ? "" : defaultDriveFolderId);
    }

    private void insertVaultDefault(long vaultId, String key, String value) {
        jdbc.update(
            "INSERT INTO vault_settings(vault_id, key, value) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            vaultId, key, value);
    }

    private void seedVaultIfBlank(long vaultId, String key, String value) {
        String v = value == null ? "" : value.trim();
        jdbc.update(
            "INSERT INTO vault_settings(vault_id, key, value) VALUES (?, ?, ?) "
            + "ON CONFLICT (vault_id, key) DO UPDATE SET value = EXCLUDED.value "
            + "WHERE vault_settings.value = ''",
            vaultId, key, v);
    }

    /**
     * Ensure the row exists, and fill it from env ONLY while the stored value is blank
     * (a value set via the Settings UI always wins over env afterwards).
     */
    private void seedIfBlank(String key, String value) {
        String v = value == null ? "" : value.trim();
        jdbc.update(
            "INSERT INTO app_settings(key, value) VALUES (?, ?) "
            + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value "
            + "WHERE app_settings.value = ''",
            key, v);
    }

    private static boolean isPlaceholder(String v) {
        return v == null || v.isBlank() || "placeholder".equals(v);
    }

    private void insertDefault(String key, String value) {
        jdbc.update(
            "INSERT INTO app_settings(key, value) VALUES (?, ?) ON CONFLICT DO NOTHING",
            key, value);
    }

    public String get(String key) {
        return jdbc.queryForObject(
            "SELECT value FROM app_settings WHERE key = ?", String.class, key);
    }

    public String getOrDefault(String key, String defaultValue) {
        List<String> rows = jdbc.queryForList(
            "SELECT value FROM app_settings WHERE key = ?", String.class, key);
        return rows.isEmpty() ? defaultValue : rows.get(0);
    }

    public void set(String key, String value) {
        jdbc.update(
            "INSERT INTO app_settings(key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
            key, value);
    }

    // ── per-vault generic access ──────────────────────────────────────────────

    public String getForVault(long vaultId, String key, String defaultValue) {
        List<String> rows = jdbc.queryForList(
            "SELECT value FROM vault_settings WHERE vault_id = ? AND key = ?", String.class, vaultId, key);
        return rows.isEmpty() ? defaultValue : rows.get(0);
    }

    public void setForVault(long vaultId, String key, String value) {
        jdbc.update(
            "INSERT INTO vault_settings(vault_id, key, value) VALUES (?, ?, ?) "
            + "ON CONFLICT (vault_id, key) DO UPDATE SET value = EXCLUDED.value",
            vaultId, key, value);
    }

    /** Non-archived vaults whose syncEnabled is true — what SyncWorker and the DB-backup cron iterate. */
    public List<Long> syncEnabledVaultIds() {
        return jdbc.queryForList(
            "SELECT s.vault_id FROM vault_settings s JOIN vaults v ON v.id = s.vault_id "
            + "WHERE s.key = 'syncEnabled' AND s.value = 'true' AND v.archived_at IS NULL "
            + "ORDER BY s.vault_id", Long.class);
    }

    /** Another vault (not {@code vaultId}) already bound to this Drive folder, or null. */
    public Long otherVaultUsingDriveFolder(long vaultId, String folderId) {
        if (folderId == null || folderId.isBlank()) return null;
        List<Long> ids = jdbc.queryForList(
            "SELECT vault_id FROM vault_settings WHERE key = 'sync.drive.folder_id' "
            + "AND value = ? AND vault_id <> ?", Long.class, folderId, vaultId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Binds a Drive folder to a vault; throws if another vault already uses it. Blank clears. */
    public void setSyncDriveFolderId(long vaultId, String folderId) {
        Long other = otherVaultUsingDriveFolder(vaultId, folderId);
        if (other != null) throw new DuplicateDriveFolderException(folderId, other);
        setForVault(vaultId, "sync.drive.folder_id", folderId == null ? "" : folderId);
    }

    public String getResourcePath(long vaultId) {
        return getForVault(vaultId, "resourcePath", vaults.containerPath(vaultId) + "/resources/images");
    }

    public void setResourcePath(long vaultId, String path) {
        setForVault(vaultId, "resourcePath", path);
    }

    public int getReviewPageSize() {
        return Integer.parseInt(get("reviewPageSize"));
    }

    public String getStartupSyncMode(long vaultId) {
        return getForVault(vaultId, "startupSyncMode", "blocking");
    }

    public void setStartupSyncMode(long vaultId, String mode) {
        setForVault(vaultId, "startupSyncMode", mode);
    }

    public int getMaxDailyReviews() {
        return Integer.parseInt(get("maxDailyReviews"));
    }

    /** Of the daily review set, how many run as flashcards (rest are read-and-self-grade). */
    public int getMaxDailyFlashcards() {
        return Integer.parseInt(getOrDefault("maxDailyFlashcards", "20"));
    }

    public int getBankruptcyLimit() {
        return Integer.parseInt(get("bankruptcyLimit"));
    }

    public String getChronoLastRunDate(long vaultId) {
        return getForVault(vaultId, "chronoLastRunDate", "");
    }

    public void setChronoLastRunDate(long vaultId, String date) {
        setForVault(vaultId, "chronoLastRunDate", date);
    }

    public String getEmbedModel() {
        return get("ollamaEmbedModel");
    }

    public boolean isFlashcardsEnabled() {
        return Boolean.parseBoolean(getOrDefault("flashcardsEnabled", "true"));
    }

    public int getChronicNeglectDays() {
        return Integer.parseInt(getOrDefault("chronicNeglectDays", "7"));
    }

    public boolean isTracksEnabled() {
        return Boolean.parseBoolean(getOrDefault("tracksEnabled", "true"));
    }

    /** Global default for sources whose own auto_accept is NULL. */
    public boolean isFeedAutoAccept() {
        return Boolean.parseBoolean(getOrDefault("feedAutoAccept", "false"));
    }

    /** How often {@code SourcePollWorker} re-checks a feed source it has already checked
     *  before (a due source with a null lastCheckedAt is always polled). */
    public long getSubscriptionPollIntervalMs() {
        return Long.parseLong(getOrDefault("subscriptionsPollIntervalMs", "21600000"));
    }

    // ── Google Drive sync ─────────────────────────────────────────────────────

    public boolean isSyncEnabled(long vaultId) {
        return Boolean.parseBoolean(getForVault(vaultId, "syncEnabled", "false"));
    }

    public void setSyncEnabled(long vaultId, boolean enabled) {
        setForVault(vaultId, "syncEnabled", String.valueOf(enabled));
    }

    public String getSyncClientId() {
        return getOrDefault("syncClientId", "");
    }

    public String getSyncClientSecret() {
        return getOrDefault("syncClientSecret", "");
    }

    public String getSyncPassphrase(long vaultId) {
        return getForVault(vaultId, "syncPassphrase", "");
    }

    public void setSyncPassphrase(long vaultId, String passphrase) {
        setForVault(vaultId, "syncPassphrase", passphrase);
    }

    public String getSyncDriveFolderId(long vaultId) {
        return getForVault(vaultId, "sync.drive.folder_id", "");
    }

    public String getSyncRefreshToken(long vaultId) {
        return getForVault(vaultId, "sync.refresh_token", "");
    }

    public String getSyncAccountEmail(long vaultId) {
        return getForVault(vaultId, "sync.account_email", "");
    }

    /** Explicit OAuth redirect URI (must match Google Cloud Console); blank = derive from origin. */
    public String getSyncOAuthRedirectUri() {
        return getOrDefault("sync.oauth.redirect_uri", "");
    }
}
