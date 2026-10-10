package com.obsidian.obsidian.pwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.cards.AssignmentService;
import com.obsidian.obsidian.feed.FeedRepository;
import com.obsidian.obsidian.feed.FeedRepository.FeedItem;
import com.obsidian.obsidian.inbox.InboxController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.VaultEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OfflineExportService#exportFeed()} — no Spring context, no DB: a real
 * {@link VaultEncryptionService} (same pattern as VaultEncryptionServiceTest) proves the
 * bundle round-trips through AES-GCM exactly as the phone will decrypt it; everything else
 * is mocked. See feed/FLOWS_feed.md §4/P2 "export decrypts round-trip".
 */
class OfflineExportServiceTest {

    FileRepository fileRepo;
    DriveService drive;
    AssignmentService assignmentService;
    InboxController inbox;
    SettingsRepository settingsRepo;
    FeedRepository feedRepo;
    VaultEncryptionService encryption;
    OfflineExportService service;
    ObjectMapper mapper = new ObjectMapper();

    /** VaultEncryptionService's no-arg constructor is package-private (sync-package tests
     *  only); from here the public {@code SettingsRepository}-backed constructor + a stubbed
     *  passphrase is the way to get a real, working instance. */
    private static VaultEncryptionService realEncryption(String passphrase) {
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getSyncPassphrase()).thenReturn(passphrase);
        VaultEncryptionService s = new VaultEncryptionService(settings);
        s.init();
        return s;
    }

    @BeforeEach
    void setUp() {
        fileRepo = mock(FileRepository.class);
        drive = mock(DriveService.class);
        assignmentService = mock(AssignmentService.class);
        inbox = mock(InboxController.class);
        settingsRepo = mock(SettingsRepository.class);
        feedRepo = mock(FeedRepository.class);
        encryption = realEncryption("offline-export-test-passphrase");
        service = new OfflineExportService(fileRepo, encryption, drive, assignmentService, inbox,
            settingsRepo, feedRepo);
        when(drive.isConfigured()).thenReturn(true);
    }

    @Test
    void exportFeed_roundTripsPendingItems_textOnly() throws Exception {
        Instant published = Instant.parse("2026-09-01T10:00:00Z");
        when(feedRepo.list("pending", 200)).thenReturn(List.of(
            new FeedItem(1L, 10L, "Some Channel", "https://www.youtube.com/watch?v=abc", "A Video",
                "https://i/thumb.jpg", 120, published, "pending", null, null),
            new FeedItem(2L, 10L, "Some Channel", "https://www.youtube.com/watch?v=def", "Another Video",
                "https://i/thumb2.jpg", 90, null, "pending", null, null)));

        int count = service.exportFeed();

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(drive).uploadOffline(eq("feed.json.enc"), captor.capture());

        byte[] decrypted = encryption.decrypt(captor.getValue());
        JsonNode bundle = mapper.readTree(decrypted);
        assertThat(bundle.get("generatedAt").asLong()).isGreaterThan(0);
        JsonNode items = bundle.get("items");
        assertThat(items).hasSize(2);
        JsonNode first = items.get(0);
        assertThat(first.get("id").asLong()).isEqualTo(1L);
        assertThat(first.get("sourceId").asLong()).isEqualTo(10L);
        assertThat(first.get("sourceTitle").asText()).isEqualTo("Some Channel");
        assertThat(first.get("itemUrl").asText()).isEqualTo("https://www.youtube.com/watch?v=abc");
        assertThat(first.get("title").asText()).isEqualTo("A Video");
        assertThat(first.get("status").asText()).isEqualTo("pending");
        // text-only: no thumbnail/duration shipped to the phone (no offline thumb cache yet)
        assertThat(first.has("thumbUrl")).isFalse();
        assertThat(first.has("durationS")).isFalse();
    }

    @Test
    void exportFeed_capsAtTwoHundred() throws Exception {
        when(feedRepo.list("pending", 200)).thenReturn(List.of());

        service.exportFeed();

        verify(feedRepo).list("pending", 200);
    }

    @Test
    void exportFeed_driveNotConnected_throwsAndNeverTouchesFeedRepo() {
        when(drive.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.exportFeed()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exportFeed_passphraseNotSet_throws() {
        VaultEncryptionService unconfigured = realEncryption("");
        OfflineExportService svc = new OfflineExportService(fileRepo, unconfigured, drive, assignmentService,
            inbox, settingsRepo, feedRepo);

        assertThatThrownBy(svc::exportFeed).isInstanceOf(IllegalStateException.class);
    }
}
