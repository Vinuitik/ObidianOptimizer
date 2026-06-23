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
 * Learn "Inbox" — the triage queue for notes the ingest agent generated. Ingest
 * parks standalone notes in the vault's {@code _inbox/} staging folder (see
 * embedder ingest/publish.py INBOX_FOLDER) with frontmatter:
 *   ingest-inbox: true
 *   ingest-source: <url>
 *   ingest-suggested-folder: <find_home guess>
 * NoteIndexRepository keeps {@code _inbox/} out of the FSRS review queue, so notes
 * sit here until the user files them.
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
                     String captureId, Integer captureSeq) {}

    // ── List staged notes ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<InboxItem>> list() {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve(INBOX_DIR);
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(List.of());
        }
        List<InboxItem> items = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .sorted((a, b) -> b.getFileName().toString()
                                     .compareToIgnoreCase(a.getFileName().toString()))
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
                              seq));
                      } catch (IOException e) {
                          log.warn("[inbox] could not read {}: {}", p, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("[inbox] list failed: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
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

            // When a capture's last proposed note leaves _inbox, the capture is fully
            // triaged. Phase 6 will trash the source here; for now just flip the status.
            if (captureId != null && noteIndex.countUnfiledNotesForCapture(captureId) == 0) {
                captureRepo.updateStatus(captureId, "filed");
                log.info("[inbox] capture {} fully filed", captureId);
            }
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[inbox] file failed for {}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
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
}
