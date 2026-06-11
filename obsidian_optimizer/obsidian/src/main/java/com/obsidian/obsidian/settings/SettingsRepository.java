package com.obsidian.obsidian.settings;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    @Value("${VAULT_PATH:C:/Users/ACER/Desktop/NewLife}")
    private String defaultVaultPath;

    @Value("${IMAGE_PATH:#{null}}")
    private String defaultImagePath;

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
        insertDefault("maxDailyReviews", "30");
        insertDefault("bankruptcyLimit", "200");
        insertDefault("chronoLastRunDate", "");
        insertDefault("ollamaEmbedModel", "mixedbread-ai/mxbai-embed-large-v1");
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

    public int getBankruptcyLimit() {
        return Integer.parseInt(get("bankruptcyLimit"));
    }

    public String getChronoLastRunDate() {
        return get("chronoLastRunDate");
    }

    public String getEmbedModel() {
        return get("ollamaEmbedModel");
    }
}
