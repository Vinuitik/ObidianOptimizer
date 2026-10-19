package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.NoteLinkRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository.SyncEntry;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SyncWorker iterates every sync-enabled vault (active or not) and each one syncs strictly
 * against its own Drive folder (here: its own vaultId handle on the mocked DriveService),
 * own passphrase, own device id and own vault root. A sync-off vault is never touched.
 */
class SyncWorkerTest {

    @TempDir Path root1;
    @TempDir Path root2;

    SyncQueueRepository   queueRepo = mock(SyncQueueRepository.class);
    VaultEncryptionService crypto   = mock(VaultEncryptionService.class);
    DriveService          drive     = mock(DriveService.class);
    DeviceIdentityService device    = mock(DeviceIdentityService.class);
    SettingsRepository    settings  = mock(SettingsRepository.class);
    ActiveVault           active    = mock(ActiveVault.class);
    DbBackupService       dbBackup  = mock(DbBackupService.class);

    SyncService service;
    SyncWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        service = new SyncService(queueRepo, crypto, drive, device, settings,
            mock(NoteIndexRepository.class), mock(NoteLinkRepository.class), mock(ImageScanService.class), active);
        worker = new SyncWorker(service, dbBackup, settings, active);

        when(active.id()).thenReturn(1L);
        when(active.rootFor(1L)).thenReturn(root1);
        when(active.rootFor(2L)).thenReturn(root2);
        when(active.rootFor(3L)).thenReturn(root2);        // never used: vault 3 is sync-off
        for (long v : new long[]{1L, 2L, 3L}) {
            when(crypto.isConfigured(v)).thenReturn(true);
            when(drive.isConfigured(v)).thenReturn(true);
            when(device.getDeviceId(v)).thenReturn("dev-" + v);
        }
        when(crypto.encrypt(anyLong(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(drive.uploadFile(anyLong(), anyString(), any(), anyString(), anyString(), any())).thenReturn("fid");
    }

    @AfterEach
    void stop() { worker.stopLane(); }

    private SyncEntry pending(String path, String hash) {
        return new SyncEntry(path, hash, "PENDING", null, null, 0);
    }

    private String write(Path root, String rel, String content) throws Exception {
        Files.writeString(root.resolve(rel), content);
        return ContentHashing.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void scheduledUpload_syncsEachEnabledVaultToItsOwnFolder_andSkipsSyncOffVaults() throws Exception {
        String h1 = write(root1, "a.md", "vault one note");
        String h2 = write(root2, "b.md", "vault two note");
        // vault 3 is sync-off: not in the enabled list
        when(settings.syncEnabledVaultIds()).thenReturn(List.of(1L, 2L));
        when(queueRepo.findUploadable(eq(1L), anyInt())).thenReturn(List.of(pending("a.md", h1)));
        when(queueRepo.findUploadable(eq(2L), anyInt())).thenReturn(List.of(pending("b.md", h2)));

        worker.scheduledUpload();

        verify(drive, timeout(5000)).uploadFile(eq(1L), eq("a.md"), any(), eq(h1), eq("dev-1"), any());
        verify(drive, timeout(5000)).uploadFile(eq(2L), eq("b.md"), any(), eq(h2), eq("dev-2"), any());
        // strictly own folder: nothing crosses over, nothing for the sync-off vault
        verify(drive, never()).uploadFile(eq(1L), eq("b.md"), any(), anyString(), anyString(), any());
        verify(drive, never()).uploadFile(eq(2L), eq("a.md"), any(), anyString(), anyString(), any());
        verify(drive, never()).uploadFile(eq(3L), anyString(), any(), anyString(), anyString(), any());
        verify(queueRepo, never()).findUploadable(eq(3L), anyInt());
        verify(crypto).encrypt(eq(1L), any());      // each vault encrypts with its own key
        verify(crypto).encrypt(eq(2L), any());
    }

    @Test
    void noSyncEnabledVaults_nothingTouched() throws Exception {
        when(settings.syncEnabledVaultIds()).thenReturn(List.of());

        worker.scheduledUpload();
        worker.scheduledDbBackup();
        worker.scheduledJanitor();

        Thread.sleep(300);
        verifyNoInteractions(drive);
        verifyNoInteractions(queueRepo);
        verifyNoInteractions(dbBackup);
    }

    @Test
    void scheduledDbBackup_runsOncePerEnabledVault() {
        when(settings.syncEnabledVaultIds()).thenReturn(List.of(1L, 2L));

        worker.scheduledDbBackup();

        verify(dbBackup, timeout(5000)).backupNow(1L);
        verify(dbBackup, timeout(5000)).backupNow(2L);
        verify(dbBackup, never()).backupNow(3L);
    }

    @Test
    void oneVaultFailing_doesNotStopTheOthers() throws Exception {
        String h2 = write(root2, "b.md", "vault two note");
        when(settings.syncEnabledVaultIds()).thenReturn(List.of(1L, 2L));
        when(queueRepo.findUploadable(eq(1L), anyInt())).thenThrow(new IllegalStateException("db hiccup"));
        when(queueRepo.findUploadable(eq(2L), anyInt())).thenReturn(List.of(pending("b.md", h2)));

        worker.scheduledUpload();

        verify(drive, timeout(5000)).uploadFile(eq(2L), eq("b.md"), any(), anyString(), eq("dev-2"), any());
    }

    @Test
    void manualUpload_targetsTheActiveVaultOnly() throws Exception {
        String h1 = write(root1, "a.md", "vault one note");
        when(queueRepo.findUploadable(eq(1L), anyInt())).thenReturn(List.of(pending("a.md", h1)));

        worker.triggerManualUpload();

        verify(drive, timeout(5000)).uploadFile(eq(1L), eq("a.md"), any(), anyString(), eq("dev-1"), any());
        verify(queueRepo, never()).findUploadable(eq(2L), anyInt());
    }
}
