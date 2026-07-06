package com.obsidian.obsidian.pwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.cards.AssignmentRepository;
import com.obsidian.obsidian.cards.AssignmentService;
import com.obsidian.obsidian.cards.ReviewService;
import com.obsidian.obsidian.inbox.InboxController;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.DriveService.MailboxFile;
import com.obsidian.obsidian.sync.VaultEncryptionService;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Drains the phone's write-back mailbox (DRIVE_OFFLINE_SYNC_ARCH §3c/§8 B2). Reads the
 * encrypted event files the PWA dropped in Drive {@code _mailbox/}, applies each event, and
 * deletes a file ONLY when every event in it committed — the correctness hinge. Idempotent
 * via ConsumedEventRepository (eventId), so a file left un-deleted after a partial failure
 * is safely reprocessed.
 *
 * Runs on boot (whenever the laptop comes online) and on a short cron while up. P3 handles
 * {@code grade} events; {@code file}/{@code discard}/{@code assignment} arrive in P4/P5 — an
 * unsupported kind leaves the file in place (no data loss) until the server learns it.
 */
@Service
public class MailboxConsumeService {

    private static final Logger log = LoggerFactory.getLogger(MailboxConsumeService.class);

    private final DriveService drive;
    private final VaultEncryptionService encryption;
    private final ReviewService reviewService;
    private final AssignmentService assignmentService;
    private final AssignmentRepository assignmentRepo;
    private final InboxController inbox;
    private final ConsumedEventRepository consumed;
    private final OfflineExportService offlineExport;
    private final ObjectMapper mapper = new ObjectMapper();

    public MailboxConsumeService(DriveService drive,
                                 VaultEncryptionService encryption,
                                 ReviewService reviewService,
                                 AssignmentService assignmentService,
                                 AssignmentRepository assignmentRepo,
                                 InboxController inbox,
                                 ConsumedEventRepository consumed,
                                 OfflineExportService offlineExport) {
        this.drive = drive;
        this.encryption = encryption;
        this.reviewService = reviewService;
        this.assignmentService = assignmentService;
        this.assignmentRepo = assignmentRepo;
        this.inbox = inbox;
        this.consumed = consumed;
        this.offlineExport = offlineExport;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void consumeOnStartup() {
        Thread t = new Thread(this::consumeAll, "mailbox-consume-startup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "${mailbox.consume.cron:0 */15 * * * *}")
    public void consumeScheduled() {
        consumeAll();
    }

    /** Drain the mailbox. Returns the number of events applied. Single-flight. */
    public synchronized int consumeAll() {
        if (!drive.isConfigured() || !encryption.isConfigured()) return 0;

        int applied = 0;
        try {
            List<MailboxFile> files = drive.listMailbox();
            for (MailboxFile f : files) {
                try {
                    applied += consumeFile(f);
                } catch (Exception e) {
                    log.warn("[Mailbox] file {} failed, left for retry: {}", f.name(), e.getMessage());
                }
            }
            if (applied > 0) {
                // The phone's next pull should reflect the grades we just applied.
                try { offlineExport.exportReviewBundle(200); }
                catch (Exception e) { log.warn("[Mailbox] re-export after consume failed: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("[Mailbox] consume pass failed: {}", e.getMessage());
        }
        return applied;
    }

    /** Apply one file's events; delete it only if ALL of them are supported + committed. */
    private int consumeFile(MailboxFile f) throws Exception {
        byte[] plain = encryption.decrypt(drive.downloadFile(f.fileId()));
        JsonNode root = mapper.readTree(plain);
        JsonNode events = root.get("events");
        if (events == null || !events.isArray()) {
            drive.deleteFile(f.fileId()); // malformed/empty → drop it
            return 0;
        }

        boolean allCommitted = true;
        int applied = 0;
        for (JsonNode ev : events) {
            String kind = ev.path("kind").asText("");
            String eventId = ev.path("eventId").asText(null);
            if (eventId != null && consumed.alreadyConsumed(eventId)) continue;
            try {
                switch (kind) {
                    case "grade"       -> applyGrade(ev);
                    case "assignment"  -> applyAssignment(ev);
                    case "file"        -> inbox.fileNote(ev.path("path").asText(),
                                              ev.path("targetFolder").asText(), ev.path("content").asText(null));
                    case "discard"     -> inbox.discardNote(ev.path("path").asText());
                    case "acknowledge" -> inbox.acknowledgeCapture(ev.path("captureId").asText());
                    default -> {
                        allCommitted = false; // unknown kind → keep the file for a newer server
                        continue;
                    }
                }
                if (eventId != null) consumed.markConsumed(eventId);
                applied++;
            } catch (Exception e) {
                allCommitted = false; // transient (embedder/judge down) or bad data → retry next pass
                log.warn("[Mailbox] {} event failed: {}", kind, e.getMessage());
            }
        }

        if (allCommitted) {
            drive.deleteFile(f.fileId());
            log.info("[Mailbox] consumed {} ({} events)", f.name(), applied);
        }
        return applied;
    }

    private void applyGrade(JsonNode ev) {
        ReviewService.Band band = ReviewService.Band.valueOf(
            ev.path("band").asText("").toUpperCase().replace(' ', '_'));
        reviewService.grade(ev.path("notePath").asText(), band);
    }

    /** Deferred grading: replay the offline flashcard answers through the real engine, then
     *  finalize FSRS. Idempotent — skips cards already attempted and an already-completed
     *  assignment, so a retried file is safe. */
    private void applyAssignment(JsonNode ev) {
        UUID assignmentId = UUID.fromString(ev.path("assignmentId").asText());
        Map<String, Object> assignment = assignmentRepo.findAssignment(assignmentId);
        if (assignment == null) {
            // Assignment expired/cleaned — nothing to grade; treat as done so we don't loop.
            log.warn("[Mailbox] assignment {} not found — skipping", assignmentId);
            return;
        }
        if (assignment.get("completed_at") == null) {
            JsonNode answers = ev.path("answers");   // { cardId: answer }
            for (Iterator<String> it = answers.fieldNames(); it.hasNext(); ) {
                String cardId = it.next();
                UUID cid = UUID.fromString(cardId);
                if (!assignmentRepo.attemptExists(assignmentId, cid)) {
                    assignmentService.submitAttempt(assignmentId, cid, answers.get(cardId).asText());
                }
            }
            assignmentService.complete(assignmentId);
        }
    }
}
