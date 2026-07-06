package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.NoteLinkRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.DriveService.DriveFileInfo;
import com.obsidian.obsidian.sync.SyncQueueRepository.SyncEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    @TempDir Path vault;

    SyncQueueRepository    queueRepo  = mock(SyncQueueRepository.class);
    VaultEncryptionService crypto     = mock(VaultEncryptionService.class);
    DriveService           drive      = mock(DriveService.class);
    DeviceIdentityService  device     = mock(DeviceIdentityService.class);
    SettingsRepository     settings   = mock(SettingsRepository.class);
    NoteIndexRepository    noteIndex  = mock(NoteIndexRepository.class);
    NoteLinkRepository     linkRepo   = mock(NoteLinkRepository.class);
    ImageScanService       imageScan  = mock(ImageScanService.class);

    SyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SyncService(queueRepo, crypto, drive, device,
                                  settings, noteIndex, linkRepo, imageScan);
        when(settings.getVaultPath()).thenReturn(vault.toString());
        when(crypto.isConfigured()).thenReturn(true);
        when(drive.isConfigured()).thenReturn(true);
        when(device.getDeviceId()).thenReturn("dev-1");
        // identity "encryption" — content assertions stay readable
        when(crypto.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(crypto.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SyncEntry pending(String path, String hash) {
        return new SyncEntry(path, hash, "PENDING", null, null, 0);
    }

    private Path writeVaultFile(String rel, String content) throws IOException {
        Path f = vault.resolve(rel);
        Files.createDirectories(f.getParent() == null ? vault : f.getParent());
        Files.writeString(f, content);
        return f;
    }

    // ── uploadPending ─────────────────────────────────────────────────────

    @Test
    void uploadSkipsWhenEncryptionNotConfigured() {
        when(crypto.isConfigured()).thenReturn(false);
        service.uploadPending();
        verifyNoInteractions(drive);
        verifyNoInteractions(queueRepo);
    }

    @Test
    void uploadSkipsWhenDriveNotConfigured() {
        when(drive.isConfigured()).thenReturn(false);
        service.uploadPending();
        verifyNoInteractions(queueRepo);
    }

    @Test
    void uploadsPendingNoteAndMarksDoneWithQueueTimeHash() throws Exception {
        String content = "# hello sync";
        writeVaultFile("folder/note.md", content);
        String actualHash = ContentHashing.sha256(
            content.getBytes(StandardCharsets.UTF_8));
        when(queueRepo.findUploadable(anyInt()))
            .thenReturn(List.of(pending("folder/note.md", actualHash)));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("drive-id-9");

        service.uploadPending();

        // uploaded bytes are hashed fresh; DONE is conditional on the queue hash
        verify(drive).uploadFile(eq("folder/note.md"),
            eq(content.getBytes(StandardCharsets.UTF_8)),
            eq(actualHash), eq("dev-1"), eq(null));
        verify(queueRepo).markDoneIfHashMatches("folder/note.md", "drive-id-9", actualHash);
        verify(queueRepo, never()).markFailed(anyString());
    }

    @Test
    void uploadHashesActualContentNotQueueTimeHash() throws Exception {
        // note edited after it was queued: file content no longer matches queue hash
        writeVaultFile("note.md", "edited content");
        String staleQueueHash  = "stale-hash";
        String actualHash = ContentHashing.sha256(
            "edited content".getBytes(StandardCharsets.UTF_8));
        when(queueRepo.findUploadable(anyInt()))
            .thenReturn(List.of(pending("note.md", staleQueueHash)));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("id");
        when(queueRepo.markDoneIfHashMatches(anyString(), anyString(), anyString()))
            .thenReturn(false); // conditional refused — row was re-marked PENDING

        service.uploadPending();

        // Drive metadata gets the hash of what actually went out…
        verify(drive).uploadFile(eq("note.md"), any(), eq(actualHash), eq("dev-1"), any());
        // …but DONE is attempted against the stale queue hash and refused, not failed
        verify(queueRepo).markDoneIfHashMatches("note.md", "id", staleQueueHash);
        verify(queueRepo, never()).markFailed(anyString());
    }

    @Test
    void uploadMarksFailedWhenFileUnreadable() throws Exception {
        when(queueRepo.findUploadable(anyInt()))
            .thenReturn(List.of(pending("ghost.md", "h")));

        service.uploadPending();

        verify(queueRepo).markFailed("ghost.md");
        verify(drive, never()).uploadFile(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadFailureOnOneFileDoesNotStopTheBatch() throws Exception {
        writeVaultFile("ok.md", "fine");
        when(queueRepo.findUploadable(anyInt()))
            .thenReturn(List.of(pending("ghost.md", "h1"), pending("ok.md", "h2")));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("id-ok");

        service.uploadPending();

        verify(queueRepo).markFailed("ghost.md");
        verify(drive).uploadFile(eq("ok.md"), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadReusesExistingDriveFileId() throws Exception {
        writeVaultFile("note.md", "v2");
        SyncEntry entry = new SyncEntry("note.md", "h", "PENDING", null, "existing-id", 1);
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of(entry));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("existing-id");

        service.uploadPending();

        verify(drive).uploadFile(eq("note.md"), any(), anyString(), anyString(),
            eq("existing-id"));
    }

    @Test
    void uploadDrivesFailedRowsReturnedByFindUploadable() throws Exception {
        // Self-healing: a previously-FAILED row still under the retry cap is retried.
        writeVaultFile("retry.md", "second chance");
        SyncEntry failedRow = new SyncEntry("retry.md", "h", "FAILED", null, null, 2);
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of(failedRow));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("id-retry");

        service.uploadPending();

        verify(drive).uploadFile(eq("retry.md"), any(), anyString(), anyString(), any());
        verify(queueRepo).markDoneIfHashMatches(eq("retry.md"), eq("id-retry"), anyString());
    }

    @Test
    void uploadProcessesEveryFileConcurrently() throws Exception {
        writeVaultFile("a/x.md", "1");
        writeVaultFile("a/y.md", "2");
        writeVaultFile("b/z.md", "3");
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of(
            pending("a/x.md", "h1"), pending("a/y.md", "h2"), pending("b/z.md", "h3")));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("id");

        service.uploadPending();

        // every queued file is uploaded (folder-safety is handled inside DriveService)
        verify(drive, times(3)).uploadFile(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadProgressIsIdleByDefaultAndClearsAfterRun() throws Exception {
        assertThat(service.uploadProgress()).containsEntry("uploading", false);

        writeVaultFile("p.md", "x");
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of(pending("p.md", "h")));
        service.uploadPending();

        // drain finished — not stuck "uploading", and total reflects the batch
        assertThat(service.uploadProgress()).containsEntry("uploading", false);
        assertThat(service.uploadProgress()).containsEntry("uploadTotal", 1);
    }

    // ── downloadAll ───────────────────────────────────────────────────────

    private DriveFileInfo driveFile(String vaultPath, String contentHash) {
        return new DriveFileInfo("fid-" + vaultPath, vaultPath, contentHash, "dev-2", 123L, 64L);
    }

    @Test
    void downloadWritesNewNoteAndUpdatesIndex() throws Exception {
        String content = "# from another device\n[[Linked Note]]";
        String hash = ContentHashing.sha256(content);
        when(drive.listAllFiles()).thenReturn(List.of(driveFile("new.md", hash)));
        when(drive.downloadFile("fid-new.md"))
            .thenReturn(content.getBytes(StandardCharsets.UTF_8));

        service.downloadAll();

        assertThat(vault.resolve("new.md")).hasContent(content);
        verify(noteIndex).upsert(eq(vault.resolve("new.md").toString()),
            eq("new"), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(linkRepo).updateLinks(eq(vault.resolve("new.md").toString()), any());
        verify(imageScan).registerImages(eq(vault.resolve("new.md").toString()), eq(content));
        verify(queueRepo).markSynced("new.md", hash, "fid-new.md");
    }

    @Test
    void downloadSkipsWhenLocalHashMatches() throws Exception {
        String content = "identical";
        writeVaultFile("same.md", content);
        when(drive.listAllFiles())
            .thenReturn(List.of(driveFile("same.md", ContentHashing.sha256(content))));

        service.downloadAll();

        verify(drive, never()).downloadFile(anyString());
        verify(queueRepo, never()).markSynced(anyString(), anyString(), anyString());
    }

    @Test
    void downloadKeepsPendingLocalEdit() throws Exception {
        writeVaultFile("edited.md", "local newer version");
        when(drive.listAllFiles())
            .thenReturn(List.of(driveFile("edited.md", "different-remote-hash")));
        when(queueRepo.findByPath("edited.md"))
            .thenReturn(pending("edited.md", "local-hash"));

        service.downloadAll();

        // LOCAL WINS: no download, no overwrite, upload still queued
        verify(drive, never()).downloadFile(anyString());
        verify(queueRepo, never()).markSynced(anyString(), anyString(), anyString());
        assertThat(vault.resolve("edited.md")).hasContent("local newer version");
    }

    @Test
    void downloadRejectsPathTraversal() throws Exception {
        when(drive.listAllFiles())
            .thenReturn(List.of(driveFile("../outside.md", "h")));

        service.downloadAll();

        verify(drive, never()).downloadFile(anyString());
        assertThat(vault.getParent().resolve("outside.md")).doesNotExist();
    }

    @Test
    void downloadWritesResourceBytesWithoutIndexing() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        String hash = ContentHashing.sha256(png);
        when(drive.listAllFiles())
            .thenReturn(List.of(driveFile("resources/images/pic.png", hash)));
        when(drive.downloadFile("fid-resources/images/pic.png")).thenReturn(png);

        service.downloadAll();

        assertThat(vault.resolve("resources/images/pic.png")).hasBinaryContent(png);
        verifyNoInteractions(noteIndex);
        verify(queueRepo).markSynced("resources/images/pic.png", hash,
            "fid-resources/images/pic.png");
    }

    @Test
    void downloadFailureOnOneFileDoesNotStopTheBatch() throws Exception {
        String good = "good content";
        String goodHash = ContentHashing.sha256(good);
        when(drive.listAllFiles()).thenReturn(List.of(
            driveFile("broken.md", "h-broken"),
            driveFile("good.md", goodHash)));
        when(drive.downloadFile("fid-broken.md")).thenThrow(new IOException("boom"));
        when(drive.downloadFile("fid-good.md"))
            .thenReturn(good.getBytes(StandardCharsets.UTF_8));

        service.downloadAll();

        assertThat(vault.resolve("good.md")).hasContent(good);
        verify(queueRepo).markSynced(eq("good.md"), eq(goodHash), anyString());
    }

    @Test
    void downloadSkipsWhenNotConfigured() throws Exception {
        when(crypto.isConfigured()).thenReturn(false);
        service.downloadAll();
        verifyNoInteractions(drive);
    }

    @Test
    void quietDownloadWritesBytesButNeverReprocesses() throws Exception {
        // Restore path: files land on disk, but NO index/link/image work is triggered
        // (the just-restored DB already holds all that — re-running would re-embed everything).
        String note = "# restored note\n[[Some Link]]";
        String hash = ContentHashing.sha256(note);
        when(drive.listAllFiles()).thenReturn(List.of(driveFile("restored.md", hash)));
        when(drive.downloadFile("fid-restored.md"))
            .thenReturn(note.getBytes(StandardCharsets.UTF_8));

        int written = service.downloadAllQuiet();

        assertThat(written).isEqualTo(1);
        assertThat(vault.resolve("restored.md")).hasContent(note);
        verifyNoInteractions(noteIndex);
        verifyNoInteractions(linkRepo);
        verifyNoInteractions(imageScan);
        verify(queueRepo, never()).markSynced(anyString(), anyString(), anyString());
    }

    @Test
    void quietDownloadSkipsFilesAlreadyOnDisk() throws Exception {
        String content = "identical";
        writeVaultFile("same.md", content);
        when(drive.listAllFiles())
            .thenReturn(List.of(driveFile("same.md", ContentHashing.sha256(content))));

        int written = service.downloadAllQuiet();

        assertThat(written).isZero();
        verify(drive, never()).downloadFile(anyString());
    }

    // ── initialScan ───────────────────────────────────────────────────────

    @Test
    void initialScanQueuesNewAndChangedNotesOnly() throws Exception {
        Path a = writeVaultFile("a.md", "alpha");
        Path b = writeVaultFile("b.md", "beta");
        when(noteIndex.getAllPathsWithHash()).thenReturn(List.of(
            Map.of("path", a.toString(), "content_hash", "hash-a"),
            Map.of("path", b.toString(), "content_hash", "hash-b")));
        // a.md already DONE with same hash → skipped; b.md unknown → queued
        when(queueRepo.findByPath("a.md"))
            .thenReturn(new SyncEntry("a.md", "hash-a", "DONE", 1L, "fid", 0));
        when(queueRepo.findByPath("b.md")).thenReturn(null);

        service.initialScan();

        verify(queueRepo, never()).markPending(eq("a.md"), anyString());
        verify(queueRepo).markPending("b.md", "hash-b");
    }

    @Test
    void initialScanQueuesResourceFiles() throws Exception {
        Path img = vault.resolve("resources/images/x.png");
        Files.createDirectories(img.getParent());
        byte[] bytes = {1, 2, 3};
        Files.write(img, bytes);
        when(noteIndex.getAllPathsWithHash()).thenReturn(List.of());
        when(queueRepo.findByPath(anyString())).thenReturn(null);

        service.initialScan();

        verify(queueRepo).markPending("resources/images/x.png",
            ContentHashing.sha256(bytes));
    }

    // ── tombstones (Drive-side delete propagation) ────────────────────────

    @Test
    void tombstonesAreTrashedAndRowsRemoved() throws Exception {
        SyncEntry doomed = new SyncEntry("gone.md", "h", "DELETE_PENDING", 1L, "fid-gone", 0);
        when(queueRepo.findByStatus("DELETE_PENDING")).thenReturn(List.of(doomed));
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of());

        service.uploadPending();

        verify(drive).trashFile("fid-gone");
        verify(queueRepo).delete("gone.md");
    }

    @Test
    void tombstoneStaysWhenTrashFails() throws Exception {
        SyncEntry doomed = new SyncEntry("gone.md", "h", "DELETE_PENDING", 1L, "fid-gone", 0);
        when(queueRepo.findByStatus("DELETE_PENDING")).thenReturn(List.of(doomed));
        when(queueRepo.findUploadable(anyInt())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IOException("boom")).when(drive).trashFile("fid-gone");

        service.uploadPending();

        verify(queueRepo, never()).delete("gone.md"); // retried next pass
    }

    @Test
    void downloadSkipsFilesWithPendingLocalDelete() throws Exception {
        DriveFileInfo df = driveFile("deleted.md", "hash-x");
        when(drive.listAllFiles()).thenReturn(List.of(df));
        when(queueRepo.findByPath("deleted.md"))
            .thenReturn(new SyncEntry("deleted.md", "h", "DELETE_PENDING", 1L, df.fileId(), 0));

        service.downloadAll();

        // downloading would resurrect the deleted file and cancel its tombstone
        verify(drive, never()).downloadFile(anyString());
        verify(queueRepo, never()).markSynced(anyString(), anyString(), anyString());
    }

    // ── janitor ───────────────────────────────────────────────────────────

    private DriveFileInfo orphan(String vaultPath, long uploadedAt) {
        return new DriveFileInfo("fid-" + vaultPath, vaultPath, "h", "dev-2", uploadedAt, 128L);
    }

    @Test
    void janitorDryRunReportsWithoutTouchingDrive() throws Exception {
        when(drive.listAllFiles()).thenReturn(List.of(orphan("old-orphan.md", 123L)));

        SyncService.JanitorResult r = service.janitor(true);

        assertThat(r.orphans()).isEqualTo(1);
        assertThat(r.freedBytes()).isEqualTo(128L);
        verify(drive, never()).trashFile(anyString());
    }

    @Test
    void janitorTrashesOldOrphansButSparesLocalFreshAndPending() throws Exception {
        writeVaultFile("alive.md", "still here");
        long now = System.currentTimeMillis();
        DriveFileInfo alive   = orphan("alive.md",   123L);   // local twin exists
        DriveFileInfo fresh   = orphan("fresh.md",   now);    // inside grace window
        DriveFileInfo queued  = orphan("queued.md",  123L);   // PENDING re-upload
        DriveFileInfo doomed  = orphan("doomed.md",  123L);   // true orphan
        when(drive.listAllFiles()).thenReturn(List.of(alive, fresh, queued, doomed));
        when(queueRepo.findByPath("queued.md"))
            .thenReturn(new SyncEntry("queued.md", "h", "PENDING", null, null, 0));

        SyncService.JanitorResult r = service.janitor(false);

        assertThat(r.orphans()).isEqualTo(1);
        assertThat(r.deletedPaths()).containsExactly("doomed.md");
        verify(drive).trashFile("fid-doomed.md");
        verify(drive, never()).trashFile("fid-alive.md");
        verify(drive, never()).trashFile("fid-fresh.md");
        verify(drive, never()).trashFile("fid-queued.md");
        verify(queueRepo).delete("doomed.md");
    }

    // ── toRelative ────────────────────────────────────────────────────────

    // Windows-only: java.nio Paths is platform-dependent, so "C:\\vault" only parses
    // as a real path on Windows. Guards SyncService.toRelative's backslash→forward-slash
    // normalization, which keeps Drive/sync keys identical across OSes. Skipped (not
    // failed) on the Linux server; still runs on Windows dev.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void toRelativeNormalizesBackslashes() {
        String rel = SyncService.toRelative("C:\\vault", "C:\\vault\\folder\\note.md");
        assertThat(rel).isEqualTo("folder/note.md");
    }
}
