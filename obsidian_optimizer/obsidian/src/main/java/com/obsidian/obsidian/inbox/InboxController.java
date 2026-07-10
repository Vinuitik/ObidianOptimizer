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

    public record InboxItem(String path, String title, String source,
                     String suggestedFolder, String content,
                     String captureId, Integer captureSeq, Integer captureSeqMinor,
                     boolean inPlace) {}

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
                              Integer seq = parseIntOrNull(frontmatterValue(content, "capture-seq"));
                              // capture-seq-minor: sub-order for notes manually split off the same
                              // source region (e.g. one PDF page range → two chapter notes). Absent
                              // ⇒ 0 (the original / un-split note). See #split endpoint.
                              Integer seqMinor = parseIntOrNull(frontmatterValue(content, "capture-seq-minor"));
                              items.add(new InboxItem(
                                  p.toAbsolutePath().toString(),
                                  title,
                                  frontmatterValue(content, "ingest-source"),
                                  suggestedFolderAbs(frontmatterValue(content, "ingest-suggested-folder")),
                                  content,
                                  emptyToNull(frontmatterValue(content, "capture-id")),
                                  seq,
                                  seqMinor,
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
                items.add(new InboxItem(path, title, null, null, content, c.id(), 1, 0, true));
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
            // Read BEFORE deleting: we need the capture + the local media path off the note.
            String content = repository.getText(req.path());
            String captureId = emptyToNull(frontmatterValue(content, "capture-id"));
            String local = localMediaValue(content);

            repository.softDeleteNote(req.path());   // de-indexes the note (drops its notes row)

            // LOCAL_MEDIA_RETENTION §4: if this was the capture's LAST surviving note (every
            // proposed note discarded, none filed), its downloaded media is orphaned → trash
            // it too. A filed note keeps its index row, so findNotesByCapture stays non-empty
            // and the file survives — "keep the source only if you kept a fragment of it".
            if (captureId != null && local != null
                    && noteIndex.findNotesByCapture(captureId).isEmpty()) {
                trashLocalMedia(local);
            }
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** The `local: …` pointer the ingest stamps into a note's `## Source` (LOCAL_MEDIA §2) —
     *  the downloaded file the note plays. Body scalar, not frontmatter. */
    private static String localMediaValue(String content) {
        var m = java.util.regex.Pattern
            .compile("(?m)^[ \\t]*local:[ \\t]*(\\S.*)$").matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Soft-delete an orphaned downloaded media file into _trash. Only touches vault-local
     *  media (resources/… or _workspace/…); external URLs have nothing to trash. */
    private void trashLocalMedia(String local) {
        String l = local.trim();
        if (l.startsWith("http://") || l.startsWith("https://")) return;
        if (!(l.startsWith("resources/") || l.startsWith("_workspace/"))) return;
        try {
            String abs = Paths.get(settingsRepo.getVaultPath()).resolve(l).normalize().toString();
            repository.softDeleteFile(abs);
            log.info("[inbox] trashed orphaned local media {}", l);
        } catch (IOException e) {
            log.warn("[inbox] could not trash local media {}: {}", l, e.getMessage());
        }
    }

    // ── Split a note into a sibling for the same source region ──────────────────
    // The ingest pipeline sometimes lumps one source region (e.g. PDF pages 21-23) into a
    // single note when it is really several chapters. Re-running ingest is expensive and
    // token-heavy, so instead of that we let the user manually spawn an ADDITIONAL note that
    // references the SAME source region and slots in right after the original. Ordering uses
    // a sub-sequence (capture-seq-minor) so no other note has to be renumbered: an original
    // at #11 gets a sibling #11-1, then #11-2, … — cheap insertion, source order preserved.
    // The new note is a duplicate the user trims down to just its chapter (both keep the same
    // `## Source` footer, so SourceSplicePanel shows the same region for each).

    @PostMapping("/split")
    public ResponseEntity<?> split(@RequestBody SplitRequest req) {
        if (req == null || req.path() == null || !req.path().contains(INBOX_DIR)) {
            return ResponseEntity.badRequest().body("inbox path required");
        }
        try {
            String content = repository.getText(req.path());
            if (content == null || content.isBlank()) {
                return ResponseEntity.badRequest().body("note not found");
            }
            String captureId = emptyToNull(frontmatterValue(content, "capture-id"));
            String source    = frontmatterValue(content, "ingest-source");
            Integer seq      = parseIntOrNull(frontmatterValue(content, "capture-seq"));
            int newMinor     = nextSplitMinor(captureId, source, seq);

            // Duplicate the original (same source region) and stamp the sub-order onto it.
            String newContent = upsertFrontmatter(content, "capture-seq-minor", String.valueOf(newMinor));

            Path inboxDir = Paths.get(settingsRepo.getVaultPath()).resolve(INBOX_DIR);
            String base = Paths.get(req.path()).getFileName().toString().replaceAll("\\.md$", "");
            String newPath = createUniqueInboxNote(inboxDir.toString(), base + " (split)", newContent);
            log.info("[inbox] split {} -> {} (capture-seq-minor {})", req.path(), newPath, newMinor);
            return ResponseEntity.ok(Map.of("path", newPath, "captureSeqMinor", newMinor));
        } catch (IOException e) {
            log.error("[inbox] split failed for {}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Next free sub-order for a note being split: 1 + the highest capture-seq-minor already
     *  present among _inbox notes that share this note's source region (same capture-id — or
     *  same ingest-source when un-captured — AND the same capture-seq). Starts at 1 so the
     *  original (implicit minor 0) keeps its bare "#N" label and siblings become #N-1, #N-2. */
    private int nextSplitMinor(String captureId, String source, Integer seq) {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve(INBOX_DIR);
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream.filter(x -> x.toString().endsWith(".md"))::iterator) {
                    String c;
                    try { c = Files.readString(p); } catch (IOException e) { continue; }
                    boolean sameGroup = captureId != null
                        ? captureId.equals(emptyToNull(frontmatterValue(c, "capture-id")))
                        : source != null && source.equals(frontmatterValue(c, "ingest-source"));
                    if (!sameGroup) continue;
                    if (!java.util.Objects.equals(seq, parseIntOrNull(frontmatterValue(c, "capture-seq")))) continue;
                    Integer m = parseIntOrNull(frontmatterValue(c, "capture-seq-minor"));
                    if (m != null && m > max) max = m;
                }
            } catch (IOException e) {
                log.warn("[inbox] split minor scan failed: {}", e.getMessage());
            }
        }
        return max + 1;
    }

    /** Create an _inbox note with pre-built content, retrying the name on collision so a
     *  second split of the same note never clobbers the first. */
    private String createUniqueInboxNote(String folderAbs, String base, String content) throws IOException {
        for (int attempt = 1; attempt <= 20; attempt++) {
            String name = attempt == 1 ? base : base + " " + attempt;
            try {
                String path = repository.createNote(folderAbs, name);
                repository.updateNote(path, content);
                return path;
            } catch (IOException e) {
                if (!String.valueOf(e.getMessage()).contains("already exists")) throw e;
            }
        }
        throw new IOException("could not find a free name for split of " + base);
    }

    /** Insert or replace a scalar key in the first frontmatter block. If the note has no
     *  frontmatter the content is returned unchanged (a split target always has some). */
    static String upsertFrontmatter(String content, String key, String value) {
        if (!content.startsWith("---\n")) return content;
        int end = content.indexOf("\n---\n", 4);
        if (end < 0) return content;
        String block = content.substring(4, end);
        String rest  = content.substring(end);   // starts with "\n---\n…"
        String line  = key + ": " + value;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?m)^" + java.util.regex.Pattern.quote(key) + ":.*$").matcher(block);
        String newBlock = m.find() ? m.replaceFirst(java.util.regex.Matcher.quoteReplacement(line))
                                   : block + "\n" + line;
        return "---\n" + newBlock + rest;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
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
     *  matches the /children folder list the UI picks from. Blank/"." → "" (UNSORTED):
     *  the classifier wasn't confident, so the triage UI shows NO pre-pick rather than
     *  defaulting the note into the vault root (which isn't a valid note home anyway). */
    private String suggestedFolderAbs(String rel) {
        if (rel == null || rel.isBlank() || rel.equals(".")) return "";
        Path root = Paths.get(settingsRepo.getVaultPath());
        return root.resolve(rel).normalize().toString();
    }

    /** Remove the inbox-only frontmatter lines when the note graduates to a folder. */
    private static String stripInboxFrontmatter(String content) {
        return content.replaceAll("(?m)^ingest-(inbox|source|suggested-folder):.*\\n", "");
    }

    record FileRequest(String path, String targetFolder, String content) {}
    record DiscardRequest(String path) {}
    record AcknowledgeRequest(String captureId) {}
    record SplitRequest(String path) {}

    // ── Programmatic API for the offline PWA (export + mailbox consume) ──────────
    // Thin delegates to the endpoint methods above, so all the triage logic + private
    // helpers stay in one place. Used by pwa/OfflineExportService + MailboxConsumeService.

    public List<InboxItem> listItems() {
        return list().getBody();
    }

    public void fileNote(String path, String targetFolder, String content) throws IOException {
        ResponseEntity<?> r = file(new FileRequest(path, targetFolder, content));
        if (!r.getStatusCode().is2xxSuccessful()) throw new IOException("file: " + r.getBody());
    }

    public void discardNote(String path) throws IOException {
        ResponseEntity<?> r = discard(new DiscardRequest(path));
        if (!r.getStatusCode().is2xxSuccessful()) throw new IOException("discard: " + r.getBody());
    }

    public void acknowledgeCapture(String captureId) throws IOException {
        ResponseEntity<?> r = acknowledge(new AcknowledgeRequest(captureId));
        if (!r.getStatusCode().is2xxSuccessful()) throw new IOException("acknowledge: " + r.getBody());
    }
}
