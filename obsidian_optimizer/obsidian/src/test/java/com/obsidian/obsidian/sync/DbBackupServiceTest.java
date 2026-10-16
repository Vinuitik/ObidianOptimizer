package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.sync.DriveService.DbBackupInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** JDBC-URL parsing (feeds pg_dump/pg_restore host/port/db args) and the restore
 *  guard (restoreBlockedReason) — the synchronous 400 check that stops a destructive
 *  pg_restore --clean from running against a non-empty DB without explicit confirmation. */
class DbBackupServiceTest {

    @Test
    void parsesStandardJdbcUrl() {
        DbBackupService.Db db = DbBackupService.parseJdbc("jdbc:postgresql://postgres:5432/obsidian");
        assertThat(db.host()).isEqualTo("postgres");
        assertThat(db.port()).isEqualTo("5432");
        assertThat(db.name()).isEqualTo("obsidian");
    }

    @Test
    void parsesUrlWithParamsAndDefaultsPort() {
        DbBackupService.Db a = DbBackupService.parseJdbc("jdbc:postgresql://db.host/mydb?sslmode=require&x=1");
        assertThat(a.host()).isEqualTo("db.host");
        assertThat(a.port()).isEqualTo("5432");   // no explicit port → default
        assertThat(a.name()).isEqualTo("mydb");
    }

    // ── restoreBlockedReason() ───────────────────────────────────────────────────

    DriveService driveService;
    VaultEncryptionService encryptionService;
    SyncService syncService;
    DeviceIdentityService deviceIdentityService;
    JdbcTemplate jdbc;
    DbBackupService backupService;

    @BeforeEach
    void setUpGuardFixtures() {
        driveService = mock(DriveService.class);
        encryptionService = mock(VaultEncryptionService.class);
        syncService = mock(SyncService.class);
        deviceIdentityService = mock(DeviceIdentityService.class);
        jdbc = mock(JdbcTemplate.class);
        backupService = new DbBackupService(driveService, encryptionService, syncService, deviceIdentityService, jdbc);
    }

    private void configuredAndEmpty() {
        when(encryptionService.isConfigured()).thenReturn(true);
        when(driveService.isConfigured()).thenReturn(true);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).thenReturn(0);
    }

    @Test
    void restoreBlockedReason_noPassphrase_blocksBeforeCheckingAnythingElse() {
        when(encryptionService.isConfigured()).thenReturn(false);

        String reason = backupService.restoreBlockedReason(false);

        assertThat(reason).isEqualTo("Encryption passphrase not set — set it first");
        verifyNoInteractions(driveService);
    }

    @Test
    void restoreBlockedReason_driveNotConnected_blocked() {
        when(encryptionService.isConfigured()).thenReturn(true);
        when(driveService.isConfigured()).thenReturn(false);

        String reason = backupService.restoreBlockedReason(false);

        assertThat(reason).isEqualTo("Google Drive not connected");
    }

    @Test
    void restoreBlockedReason_nonEmptyDbWithoutForce_blocked() {
        when(encryptionService.isConfigured()).thenReturn(true);
        when(driveService.isConfigured()).thenReturn(true);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).thenReturn(42);

        String reason = backupService.restoreBlockedReason(false);

        assertThat(reason).isEqualTo("Database is not empty — confirm to overwrite");
    }

    @Test
    void restoreBlockedReason_nonEmptyDbWithForce_skipsEmptyCheck() throws Exception {
        when(encryptionService.isConfigured()).thenReturn(true);
        when(driveService.isConfigured()).thenReturn(true);
        when(driveService.latestDbBackup()).thenReturn(Optional.of(
            new DbBackupInfo("id1", "dump.enc", 1735689600000L, 100L)));

        String reason = backupService.restoreBlockedReason(true);

        assertThat(reason).isNull();
        // force=true must never even query emptiness — it's irrelevant to the decision.
        verify(jdbc, never()).queryForObject("SELECT COUNT(*) FROM notes", Integer.class);
    }

    @Test
    void restoreBlockedReason_noBackupInDrive_blocked() throws Exception {
        configuredAndEmpty();
        when(driveService.latestDbBackup()).thenReturn(Optional.empty());

        String reason = backupService.restoreBlockedReason(false);

        assertThat(reason).isEqualTo("No DB backup found in Drive");
    }

    @Test
    void restoreBlockedReason_driveLookupThrows_reportedNotSilentlyAllowed() throws Exception {
        configuredAndEmpty();
        when(driveService.latestDbBackup()).thenThrow(new java.io.IOException("quota exceeded"));

        String reason = backupService.restoreBlockedReason(false);

        assertThat(reason).isEqualTo("Could not reach Drive: quota exceeded");
    }

    @Test
    void restoreBlockedReason_allClear_returnsNull() throws Exception {
        configuredAndEmpty();
        when(driveService.latestDbBackup()).thenReturn(Optional.of(
            new DbBackupInfo("id1", "dump.enc", 1735689600000L, 100L)));

        assertThat(backupService.restoreBlockedReason(false)).isNull();
    }

    // ── statusFragment() / progressFragment() ────────────────────────────────────

    @Test
    void statusFragment_driveNotConfigured_reportsNoBackupWithoutCallingDrive() throws Exception {
        when(driveService.isConfigured()).thenReturn(false);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).thenReturn(0);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> status = backupService.statusFragment();

        assertThat(status.get("dbEmpty")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> backup = (java.util.Map<String, Object>) status.get("dbBackup");
        assertThat(backup.get("exists")).isEqualTo(false);
        verify(driveService, never()).listDbBackups();
    }

    @Test
    void statusFragment_driveConfiguredWithBackups_exposesLatestBackupMetadata() throws Exception {
        when(driveService.isConfigured()).thenReturn(true);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).thenReturn(5);
        when(driveService.listDbBackups()).thenReturn(List.of(
            new DbBackupInfo("newest", "b2.enc", 1738368000000L, 200L),
            new DbBackupInfo("older",  "b1.enc", 1735689600000L, 100L)));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> status = backupService.statusFragment();

        assertThat(status.get("dbEmpty")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> backup = (java.util.Map<String, Object>) status.get("dbBackup");
        assertThat(backup.get("exists")).isEqualTo(true);
        assertThat(backup.get("count")).isEqualTo(2);
        // Must report the NEWEST entry's metadata, not just "some" backup.
        assertThat(backup.get("lastBackupAt")).isEqualTo(1738368000000L);
        assertThat(backup.get("sizeBytes")).isEqualTo(200L);
    }

    @Test
    void progressFragment_isInMemoryOnly_neverTouchesDriveOrJdbc() {
        java.util.Map<String, Object> progress = backupService.progressFragment();

        assertThat(progress.get("dbBackupRunning")).isEqualTo(false);
        assertThat(progress.get("dbRestoring")).isEqualTo(false);
        assertThat(progress.get("dbRestorePhase")).isEqualTo("");
        verifyNoInteractions(driveService, jdbc);
    }
}
