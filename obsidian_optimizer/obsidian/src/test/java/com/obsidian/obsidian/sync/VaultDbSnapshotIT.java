package com.obsidian.obsidian.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-vault DB backup (D1): vault A's snapshot holds only A's rows (incl. FK-owned children
 *  without a vault_id column), globals ride only with the primary, restore is per vault + atomic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class VaultDbSnapshotIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try { VAULT = Files.createTempDirectory("obsidian-vdbsnap-vault"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired com.obsidian.obsidian.vaults.ActiveVault activeVault;

    VaultDbSnapshotter snap;
    long trackA, trackB;

    @BeforeEach
    void seed() {
        activeVault.set(1L, VAULT);
        snap = new VaultDbSnapshotter(jdbc);
        jdbc.execute("TRUNCATE track_items, track_schedule, track_item_groups, tracks, workspace_items CASCADE");
        jdbc.update("INSERT INTO vaults(id, name, container_path, active, is_primary) VALUES (2, 'B', '/vault-b-it', false, false) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO workspace_items(filename, type, importance, added_at, vault_id) VALUES ('a.md','note',1,1,1), ('b-secret.md','note',1,1,2)");
        trackA = jdbc.queryForObject("INSERT INTO tracks(title,type,vault_id) VALUES ('track-A','course',1) RETURNING id", Long.class);
        trackB = jdbc.queryForObject("INSERT INTO tracks(title,type,vault_id) VALUES ('track-B-secret','course',2) RETURNING id", Long.class);
        jdbc.update("INSERT INTO track_items(track_id, position, title) VALUES (?,0,'item-A'), (?,0,'item-B-secret')", trackA, trackB);
        jdbc.update("INSERT INTO app_settings(key, value) VALUES ('it.global','g') ON CONFLICT (key) DO UPDATE SET value='g'");
    }

    private String allText(byte[] zip) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null)
                sb.append(e.getName()).append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    @Test
    void nonPrimaryBackup_hasOnlyThatVaultsRows_noGlobals() throws Exception {
        String a = allText(snap.export(1L, false));
        assertThat(a).contains("a.md", "track-A", "item-A");
        assertThat(a).doesNotContain("b-secret", "track-B-secret", "item-B-secret", "it.global", "data/app_settings.copy");

        String b = allText(snap.export(2L, false));
        assertThat(b).contains("b-secret.md", "track-B-secret", "item-B-secret");
        assertThat(b).doesNotContain("a.md", "track-A", "item-A");
    }

    @Test
    void primaryBackup_carriesGlobalsButStillNoOtherVaultRows() throws Exception {
        String a = allText(snap.export(1L, true));
        assertThat(a).contains("data/app_settings.copy", "it.global", "data/vaults.copy");
        assertThat(a).doesNotContain("b-secret", "track-B-secret", "item-B-secret");
    }

    @Test
    void restore_replacesOnlyThatVault_andKeepsOtherVaultUntouched() throws Exception {
        byte[] backup = snap.export(1L, false);
        jdbc.update("DELETE FROM workspace_items WHERE vault_id = 1");
        jdbc.update("DELETE FROM tracks WHERE id = ?", trackA);               // cascades items
        jdbc.update("INSERT INTO workspace_items(filename, type, importance, added_at, vault_id) VALUES ('stale.md','note',1,1,1)");

        snap.restore(1L, false, backup);

        assertThat(jdbc.queryForList("SELECT filename FROM workspace_items WHERE vault_id=1", String.class)).containsExactly("a.md");
        assertThat(jdbc.queryForList("SELECT title FROM track_items WHERE track_id=?", String.class, trackA)).containsExactly("item-A");
        assertThat(jdbc.queryForList("SELECT filename FROM workspace_items WHERE vault_id=2", String.class)).containsExactly("b-secret.md");
        assertThat(jdbc.queryForList("SELECT title FROM track_items WHERE track_id=?", String.class, trackB)).containsExactly("item-B-secret");
        // sequence realigned: a fresh insert must not collide with the restored ids
        jdbc.update("INSERT INTO tracks(title,type,vault_id) VALUES ('after','course',1)");
    }

    @Test
    void restore_neverOverwritesDeviceLocalSecrets_vaultAndGlobal() throws Exception {
        jdbc.update("INSERT INTO vault_settings(vault_id,key,value) VALUES (1,'sync.refresh_token','THIS-DEVICE') ON CONFLICT (vault_id,key) DO UPDATE SET value=EXCLUDED.value");
        jdbc.update("INSERT INTO vault_settings(vault_id,key,value) VALUES (1,'sync.device_id','dev-here') ON CONFLICT (vault_id,key) DO UPDATE SET value=EXCLUDED.value");
        jdbc.update("INSERT INTO vault_settings(vault_id,key,value) VALUES (1,'syncEnabled','true') ON CONFLICT (vault_id,key) DO UPDATE SET value=EXCLUDED.value");
        jdbc.update("INSERT INTO app_settings(key,value) VALUES ('syncClientSecret','SECRET-HERE') ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value");
        byte[] backup = snap.export(1L, true);
        // Simulate the backup coming from another device: tamper stored values after export.
        jdbc.update("UPDATE vault_settings SET value='OTHER-DEVICE' WHERE vault_id=1 AND key='sync.refresh_token'");
        jdbc.update("UPDATE vault_settings SET value='dev-here' WHERE vault_id=1 AND key='sync.device_id'");
        jdbc.update("UPDATE vault_settings SET value='false' WHERE vault_id=1 AND key='syncEnabled'");
        jdbc.update("UPDATE app_settings SET value='SECRET-LOCAL-NEW' WHERE key='syncClientSecret'");
        jdbc.update("DELETE FROM vault_settings WHERE vault_id=1 AND key='sync.device_id'");   // absent locally

        snap.restore(1L, true, backup);

        assertThat(jdbc.queryForObject("SELECT value FROM vault_settings WHERE vault_id=1 AND key='sync.refresh_token'", String.class)).isEqualTo("OTHER-DEVICE");
        assertThat(jdbc.queryForList("SELECT value FROM vault_settings WHERE vault_id=1 AND key='sync.device_id'", String.class)).isEmpty();   // absent stays absent
        assertThat(jdbc.queryForObject("SELECT value FROM app_settings WHERE key='syncClientSecret'", String.class)).isEqualTo("SECRET-LOCAL-NEW");
        // non-secret per-vault setting IS restored from the backup
        assertThat(jdbc.queryForObject("SELECT value FROM vault_settings WHERE vault_id=1 AND key='syncEnabled'", String.class)).isEqualTo("true");
    }

    @Test
    void restore_isAtomic_failureRollsBackDeletes() throws Exception {
        byte[] backup = snap.export(1L, false);
        // Backup claims a column that no longer exists → fails after the DELETEs.
        String tampered = new String(readManifest(backup), StandardCharsets.UTF_8).replace("\"filename\"", "\"filename_gone\"");
        byte[] bad = replaceManifest(backup, tampered);

        assertThatThrownBy(() -> snap.restore(1L, false, bad)).hasMessageContaining("Schema drift");
        assertThat(jdbc.queryForList("SELECT filename FROM workspace_items WHERE vault_id=1", String.class)).containsExactly("a.md");
    }

    @Test
    void restore_refusesBackupOfAnotherVault() throws Exception {
        byte[] bOfB = snap.export(2L, false);
        assertThatThrownBy(() -> snap.restore(1L, false, bOfB)).hasMessageContaining("belongs to vault 2");
    }

    private byte[] readManifest(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) if (e.getName().equals("manifest.json")) return in.readAllBytes();
        }
        throw new IllegalStateException("no manifest");
    }

    private byte[] replaceManifest(byte[] zip, String manifest) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
             ZipOutputStream out = new ZipOutputStream(bos)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(e.getName().equals("manifest.json") ? manifest.getBytes(StandardCharsets.UTF_8) : in.readAllBytes());
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
