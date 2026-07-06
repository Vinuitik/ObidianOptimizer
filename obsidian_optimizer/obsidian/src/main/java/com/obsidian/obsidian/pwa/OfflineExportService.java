package com.obsidian.obsidian.pwa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.cards.AssignmentService;
import com.obsidian.obsidian.inbox.InboxController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.VaultEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → phone export for the offline PWA (DRIVE_OFFLINE_SYNC_ARCH §8 B1). Builds the
 * due-review bundle (same data as {@code /api/review/bundle}), encrypts it with the vault
 * passphrase, and writes it to Drive {@code _offline/review-bundle.json.enc}. The phone then
 * pulls that ONE file and works offline — the laptop never has to be reachable at review time.
 *
 * Trigger cadence is belt-and-suspenders because the laptop is often OFF:
 *  - on startup (ApplicationReadyEvent) — refresh whenever the laptop comes online,
 *  - nightly cron — top-up while it happens to be up,
 *  - manual (POST /api/pwa/export) — "prep offline set before I leave".
 * All three are idempotent (the export is a singleton file, overwritten each time).
 */
@Service
public class OfflineExportService {

    private static final Logger log = LoggerFactory.getLogger(OfflineExportService.class);
    static final String REVIEW_BUNDLE = "review-bundle.json.enc";
    static final String CARDS_BUNDLE = "cards.json.enc";
    static final String INBOX_BUNDLE = "inbox.json.enc";
    private static final int DEFAULT_LIMIT = 200;
    private static final int SESSION_POINTS = 10;   // matches the online FlashcardSession budget
    private static final int CARDS_NOTE_LIMIT = 50; // pre-build assignments for this many due notes

    private final FileRepository repository;
    private final VaultEncryptionService encryption;
    private final DriveService drive;
    private final AssignmentService assignmentService;
    private final InboxController inbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public OfflineExportService(FileRepository repository,
                                VaultEncryptionService encryption,
                                DriveService drive,
                                AssignmentService assignmentService,
                                InboxController inbox) {
        this.repository = repository;
        this.encryption = encryption;
        this.drive = drive;
        this.assignmentService = assignmentService;
        this.inbox = inbox;
    }

    /** Build + encrypt + upload the due-notes bundle. Returns the note count. */
    public int exportReviewBundle(int limit) throws Exception {
        if (!drive.isConfigured())      throw new IllegalStateException("Drive not connected");
        if (!encryption.isConfigured()) throw new IllegalStateException("Sync passphrase not set");

        FileRepository.ReviewPage page = repository.getReviewNotesPaged(0, Math.min(limit, 500));
        List<Map<String, Object>> notes = new ArrayList<>();
        for (String path : page.notes()) {
            String content = repository.getText(path);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("path", path);
            rec.put("shortName", shortName(path));
            rec.put("content", content == null ? "" : content);
            notes.add(rec);
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("generatedAt", System.currentTimeMillis());
        bundle.put("notes", notes);

        byte[] encrypted = encryption.encrypt(mapper.writeValueAsBytes(bundle));
        drive.uploadOffline(REVIEW_BUNDLE, encrypted);
        log.info("[OfflineExport] exported {} due notes → _offline/{}", notes.size(), REVIEW_BUNDLE);
        return notes.size();
    }

    /** Pre-build a flashcard assignment per due note (reusing the real assignment engine, so
     *  grading on consume is identical) → _offline/cards.json.enc. The phone runs these tests
     *  offline and records answers; the server grades them on mailbox consume (deferred). */
    public int exportCards(int noteLimit) throws Exception {
        if (!drive.isConfigured())      throw new IllegalStateException("Drive not connected");
        if (!encryption.isConfigured()) throw new IllegalStateException("Sync passphrase not set");

        FileRepository.ReviewPage page = repository.getReviewNotesPaged(0, Math.min(noteLimit, 200));
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (String path : page.notes()) {
            try {
                Map<String, Object> a = assignmentService.build(path, SESSION_POINTS);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("assignmentId", a.get("id"));
                rec.put("notePath", path);
                rec.put("cards", a.get("cards"));
                rec.put("variants", a.get("variants"));
                assignments.add(rec);
            } catch (IllegalArgumentException e) {
                // No active cards for this note — the phone falls back to self-rated review.
            } catch (Exception e) {
                log.warn("[OfflineExport] card build failed for {}: {}", path, e.getMessage());
            }
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("generatedAt", System.currentTimeMillis());
        bundle.put("assignments", assignments);

        byte[] encrypted = encryption.encrypt(mapper.writeValueAsBytes(bundle));
        drive.uploadOffline(CARDS_BUNDLE, encrypted);
        log.info("[OfflineExport] exported {} assignments → _offline/{}", assignments.size(), CARDS_BUNDLE);
        return assignments.size();
    }

    /** The Learn inbox → _offline/inbox.json.enc, so triage works offline. Reuses the
     *  same list the desktop Learn view shows (InboxController.listItems). */
    public int exportInbox() throws Exception {
        if (!drive.isConfigured())      throw new IllegalStateException("Drive not connected");
        if (!encryption.isConfigured()) throw new IllegalStateException("Sync passphrase not set");

        List<?> items = inbox.listItems();
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("generatedAt", System.currentTimeMillis());
        bundle.put("items", items == null ? List.of() : items);

        byte[] encrypted = encryption.encrypt(mapper.writeValueAsBytes(bundle));
        drive.uploadOffline(INBOX_BUNDLE, encrypted);
        int n = items == null ? 0 : items.size();
        log.info("[OfflineExport] exported {} inbox items → _offline/{}", n, INBOX_BUNDLE);
        return n;
    }

    /** All bundles — used by the boot/nightly/manual triggers. */
    public void exportAll() throws Exception {
        exportReviewBundle(DEFAULT_LIMIT);
        exportCards(CARDS_NOTE_LIMIT);
        exportInbox();
    }

    @Scheduled(cron = "${offline.export.cron:0 30 3 * * *}")
    public void scheduledExport() {
        tryExport("scheduled");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void exportOnStartup() {
        // Off the boot thread — building bundles reads note text, builds assignments, hits Drive.
        Thread t = new Thread(() -> tryExport("startup"), "offline-export-startup");
        t.setDaemon(true);
        t.start();
    }

    private void tryExport(String why) {
        if (!drive.isConfigured() || !encryption.isConfigured()) return;
        try {
            exportAll();
        } catch (Exception e) {
            log.warn("[OfflineExport] {} export failed: {}", why, e.getMessage());
        }
    }

    private static String shortName(String path) {
        return path.replaceAll(".*[/\\\\]", "").replaceAll("\\.md$", "");
    }
}
