package com.obsidian.obsidian.settings;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SettingsRepository {

    private final JdbcTemplate jdbc;

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

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        insertDefault("resourcePath", imagePath);
        insertDefault("reviewPageSize", "20");
        insertDefault("startupSyncMode", "blocking");
        insertDefault("maxDailyReviews", defaultMaxDailyReviews);
        insertDefault("maxDailyFlashcards", defaultMaxDailyFlashcards);
        insertDefault("bankruptcyLimit", "200");
        insertDefault("chronoLastRunDate", "");
        insertDefault("ollamaEmbedModel", "mixedbread-ai/mxbai-embed-large-v1");
        insertDefault("sync.device_id", "");
        insertDefault("flashcardsEnabled", "true");
        insertDefault("chronicNeglectDays", "7");
        // Learning Tracks — default ON (finished work ships live, not behind a flag).
        insertDefault("tracksEnabled", "true");
        insertDefault("subscriptionsPollIntervalMs", "3600000");

        // Google Drive sync (SyncController / DriveService / VaultEncryptionService).
        // Env-backed keys use seedIfBlank, NOT insertDefault: on live installs the rows
        // already exist (possibly as ''), and adding a var to .env later must still land.
        insertDefault("syncEnabled", "false");
        insertDefault("sync.refresh_token", "");
        insertDefault("sync.account_email", "");
        seedIfBlank("syncClientId", defaultOAuthClientId);
        seedIfBlank("syncClientSecret", defaultOAuthClientSecret);
        seedIfBlank("sync.oauth.redirect_uri", defaultOAuthRedirectUri);
        seedIfBlank("syncPassphrase", defaultSyncPassphrase);
        seedIfBlank("sync.drive.folder_id", isPlaceholder(defaultDriveFolderId) ? "" : defaultDriveFolderId);
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

    public String getVaultPath() {
        return get("vaultPath");
    }

    public String getResourcePath() {
        return get("resourcePath");
    }

    public int getReviewPageSize() {
        return Integer.parseInt(get("reviewPageSize"));
    }

    public String getStartupSyncMode() {
        return get("startupSyncMode");
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

    public String getChronoLastRunDate() {
        return get("chronoLastRunDate");
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

    /** How often {@code SubscriptionPollWorker} re-checks a subscription track it has
     *  already checked before (a due track with a null lastCheckedAt is always polled). */
    public long getSubscriptionPollIntervalMs() {
        return Long.parseLong(getOrDefault("subscriptionsPollIntervalMs", "3600000"));
    }

    // ── Google Drive sync ─────────────────────────────────────────────────────

    public boolean isSyncEnabled() {
        return Boolean.parseBoolean(getOrDefault("syncEnabled", "false"));
    }

    public String getSyncClientId() {
        return getOrDefault("syncClientId", "");
    }

    public String getSyncClientSecret() {
        return getOrDefault("syncClientSecret", "");
    }

    public String getSyncPassphrase() {
        return getOrDefault("syncPassphrase", "");
    }

    public String getSyncDriveFolderId() {
        return getOrDefault("sync.drive.folder_id", "");
    }

    public String getSyncRefreshToken() {
        return getOrDefault("sync.refresh_token", "");
    }

    public String getSyncAccountEmail() {
        return getOrDefault("sync.account_email", "");
    }

    /** Explicit OAuth redirect URI (must match Google Cloud Console); blank = derive from origin. */
    public String getSyncOAuthRedirectUri() {
        return getOrDefault("sync.oauth.redirect_uri", "");
    }
}
