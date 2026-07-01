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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(queueRepo.findByStatus("PENDING"))
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
        when(queueRepo.findByStatus("PENDING"))
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
        when(queueRepo.findByStatus("PENDING"))
            .thenReturn(List.of(pending("ghost.md", "h")));

        service.uploadPending();

        verify(queueRepo).markFailed("ghost.md");
        verify(drive, never()).uploadFile(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadFailureOnOneFileDoesNotStopTheBatch() throws Exception {
        writeVaultFile("ok.md", "fine");
        when(queueRepo.findByStatus("PENDING"))
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
        when(queueRepo.findByStatus("PENDING")).thenReturn(List.of(entry));
        when(drive.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("existing-id");

        service.uploadPending();

        verify(drive).uploadFile(eq("note.md"), any(), anyString(), anyString(),
            eq("existing-id"));
    }

    // ── downloadAll ───────────────────────────────────────────────────────

    private DriveFileInfo driveFile(String vaultPath, String contentHash) {
        return new DriveFileInfo("fid-" + vaultPath, vaultPath, contentHash, "dev-2", 123L);
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
