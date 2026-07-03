package com.obsidian.obsidian.inbox;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Learn "Inbox" — the triage queue for anything the ingest agent touched. Two
 * shapes feed it:
 *   - standalone: new notes physically staged in {@code _inbox/} (frontmatter
 *     ingest-inbox/ingest-source/ingest-suggested-folder). NoteIndexRepository
 *     keeps {@code _inbox/} out of the FSRS review queue until filed.
 *   - in-place: an existing note rewritten below a resource embed. It never
 *     leaves its real folder or FSRS rotation — found via capture_id instead
 *     of a directory scan. Its Capture's source is a pre-rewrite snapshot of
 *     the note (not the embedded media — a note can hold several embeds, a
 *     Capture always has exactly one source) under {@code _inbox/_sources/}.
 * Filing a standalone note moves it to a real folder; acknowledging an in-place
 * note just flips the capture status — either way, once a capture's notes are
 * all triaged its source snapshot is soft-deleted to {@code _trash/}.
 *
 * Session-authenticated (SecurityConfig: anyRequest authenticated). nginx strips
 * {@code /api/}, so these map to {@code /api/inbox}.
 */
@RestController
@RequestMapping("/inbox")
public class InboxController {

    private static final Logger log = LoggerFactory.getLogger(InboxController.class);
    private static final String INBOX_DIR = "_inbox";

    private final FileRepository repository;
    private final SettingsRepository settingsRepo;
    private final NoteIndexRepository noteIndex;
    private final CaptureRepository captureRepo;

    public InboxController(FileRepository repository, SettingsRepository settingsRepo,
                           NoteIndexRepository noteIndex, CaptureRepository captureRepo) {
        this.repository = repository;
        this.settingsRepo = settingsRepo;
        this.noteIndex = noteIndex;
        this.captureRepo = captureRepo;
    }

    record InboxItem(String path, String title, String source,
                     String suggestedFolder, String content,
                     String captureId, Integer captureSeq, boolean inPlace) {}

    // ── List staged notes ──────────────────────────────────────────────────────

    /**
     * Two sources feed the same queue: standalone notes physically staged in
     * _inbox/, and in-place notes that never left their real folder (found via
     * capture_id instead of a directory scan — see INGESTION_V2_FLOWS).
     */
    @GetMapping
    public ResponseEntity<List<InboxItem>> list() {
        List<InboxItem> items = new ArrayList<>();

        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve(INBOX_DIR);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> {
                          try {
                              String content = Files.readString(p);
                              String title = p.getFileName().toString().replaceAll("\\.md$", "");
                              String seqRaw = frontmatterValue(content, "capture-seq");
                              Integer seq = null;
                              try { if (!seqRaw.isBlank()) seq = Integer.parseInt(seqRaw); }
                              catch (NumberFormatException ignored) {}
                              items.add(new InboxItem(
                                  p.toAbsolutePath().toString(),
                                  title,
                                  frontmatterValue(content, "ingest-source"),
                                  suggestedFolderAbs(frontmatterValue(content, "ingest-suggested-folder")),
                                  content,
                                  emptyToNull(frontmatterValue(content, "capture-id")),
                                  seq,
                                  false));
                          } catch (IOException e) {
                              log.warn("[inbox] could not read {}: {}", p, e.getMessage());
                          }
                      });
            } catch (IOException e) {
                log.error("[inbox] list failed: {}", e.getMessage());
            }
        }

        for (CaptureRepository.Capture c : captureRepo.listAll()) {
            if (!"note".equals(c.sourceType())) continue;
            if (!"processing".equals(c.status()) && !"ready".equals(c.status())) continue;
            for (String path : noteIndex.findNotesByCapture(c.id())) {
                String content = repository.getText(path);
                String title = Paths.get(path).getFileName().toString().replaceAll("\\.md$", "");
                items.add(new InboxItem(path, title, null, null, content, c.id(), 1, true));
            }
        }

        items.sort((a, b) -> b.path().compareToIgnoreCase(a.path()));
        return ResponseEntity.ok(items);
    }

    // ── File a note: save edits + move to a real folder ─────────────────────────

    @PostMapping("/file")
    public ResponseEntity<?> file(@RequestBody FileRequest req) {
        if (req == null || req.path() == null || req.targetFolder() == null) {
            return ResponseEntity.badRequest().body("path and targetFolder required");
        }
        if (!req.path().contains(INBOX_DIR)) {
            return ResponseEntity.badRequest().body("path is not an inbox note");
        }
        try {
            Path target = Paths.get(req.targetFolder());
            Files.createDirectories(target);

            String content = req.content() != null ? req.content() : repository.getText(req.path());
            // Keep capture-id / capture-seq on the filed note (durable link back to its
            // Capture); only the inbox-only ingest-* keys are stripped on graduation.
            String captureId = emptyToNull(frontmatterValue(content, "capture-id"));
            repository.updateNote(req.path(), stripInboxFrontmatter(content));
            String newPath = repository.moveNote(req.path(), req.targetFolder());
            log.info("[inbox] filed {} -> {}", req.path(), newPath);

            // When a capture's last proposed note leaves _inbox, the capture is fully triaged.
            if (captureId != null && noteIndex.countUnfiledNotesForCapture(captureId) == 0) {
                fileCapture(captureId);
            }
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[inbox] file failed for {}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── Acknowledge an in-place note (no folder to move to — it's already home) ──

    @PostMapping("/acknowledge")
    public ResponseEntity<?> acknowledge(@RequestBody AcknowledgeRequest req) {
        if (req == null || req.captureId() == null || req.captureId().isBlank()) {
            return ResponseEntity.badRequest().body("captureId required");
        }
        try {
            fileCapture(req.captureId());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[inbox] acknowledge failed for {}: {}", req.captureId(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Mark a capture filed and soft-delete its source snapshot to _trash/. */
    private void fileCapture(String captureId) throws IOException {
        captureRepo.updateStatus(captureId, "filed");
        log.info("[inbox] capture {} fully filed", captureId);
        CaptureRepository.Capture c = captureRepo.get(captureId);
        if (c != null && c.sourcePath() != null && !c.sourcePath().isBlank()) {
            String abs = Paths.get(settingsRepo.getVaultPath()).resolve(c.sourcePath()).toString();
            repository.softDeleteFile(abs);
            log.info("[inbox] trashed capture {} source {}", captureId, c.sourcePath());
        }
    }

    // ── Discard a generated note ────────────────────────────────────────────────

    @DeleteMapping
    public ResponseEntity<?> discard(@RequestBody DiscardRequest req) {
        if (req == null || req.path() == null || !req.path().contains(INBOX_DIR)) {
            return ResponseEntity.badRequest().body("inbox path required");
        }
        try {
            repository.softDeleteNote(req.path());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** First-block frontmatter scalar lookup (cheap; avoids a YAML dep). */
    private static String frontmatterValue(String content, String key) {
        if (!content.startsWith("---\n")) return "";
        int end = content.indexOf("\n---\n", 4);
        if (end < 0) return "";
        for (String line : content.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(key)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Convert the stored vault-relative suggestion to an absolute path so it
     *  matches the /children folder list the UI picks from. Blank/"." → vault root. */
    private String suggestedFolderAbs(String rel) {
        Path root = Paths.get(settingsRepo.getVaultPath());
        if (rel == null || rel.isBlank() || rel.equals(".")) return root.toString();
        return root.resolve(rel).normalize().toString();
    }

    /** Remove the inbox-only frontmatter lines when the note graduates to a folder. */
    private static String stripInboxFrontmatter(String content) {
        return content.replaceAll("(?m)^ingest-(inbox|source|suggested-folder):.*\\n", "");
    }

    record FileRequest(String path, String targetFolder, String content) {}
    record DiscardRequest(String path) {}
    record AcknowledgeRequest(String captureId) {}
}
