package com.obsidian.obsidian.notes;

import com.obsidian.obsidian.vaults.VaultKeys;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("vaultRepository")
public class NoteIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(NoteIndexRepository.class);

    private final JdbcTemplate jdbc;
    private final NoteLinkRepository noteLinkRepo;

    public NoteIndexRepository(JdbcTemplate jdbc, NoteLinkRepository noteLinkRepo) {
        this.jdbc = jdbc;
        this.noteLinkRepo = noteLinkRepo;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS notes (
                path        TEXT    PRIMARY KEY,
                title       TEXT    NOT NULL,
                sr_due      DATE,
                sr_interval INT,
                sr_ease     INT,
                modified_at BIGINT  NOT NULL
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_notes_sr_due ON notes(sr_due)");
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS content_hash TEXT");
        // content_hash of the version whose text chunks are in note_chunks —
        // the diff against content_hash IS the embedding work list (NoteEmbeddingWorker)
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS embedded_hash TEXT");
        // Readiness gate: true while the note still has an un-ingested A/V/PDF embed
        // (ResourceScanService maintains it at the registerImages chokepoint). The
        // embedding and card worklists skip pending notes so they aren't processed
        // against pre-transcript content — avoids rework and double LLM card spend.
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS ingest_pending BOOLEAN NOT NULL DEFAULT false");
        // body_hash = SHA-256 of the note with YAML frontmatter stripped. The card
        // work-list diffs on THIS, not content_hash, so the frontmatter rewrites that
        // happen on every review (sr-due) and chrono run don't re-trigger flashcard
        // generation (expensive LLM). content_hash stays full-file for Drive sync.
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS body_hash TEXT");
        // capture_id / capture_seq mirror the note's frontmatter (capture-id / capture-seq):
        // which Capture produced this proposed note, and its order within that capture
        // (chapter 1 before chapter 2, t=0 before t=600). The DB column is a rebuildable
        // index — frontmatter is the source of truth (see CAPTURE_ARCH.md). Lets the Learn
        // queue join a resource to its ordered notes without scanning files.
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS capture_id TEXT");
        jdbc.execute(
            "ALTER TABLE notes ADD COLUMN IF NOT EXISTS capture_seq INT");
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_notes_capture_id ON notes(capture_id)");
        jdbc.execute("ALTER TABLE notes ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE notes ALTER COLUMN vault_id DROP DEFAULT");
        VaultKeys.swapPrimaryKey(jdbc, "notes", "path");
    }

    /** Proposed-note paths for a capture, in source order (capture_seq). Used by the
     *  Learn queue to render a resource's notes without shuffling. */
    public List<String> findNotesByCapture(long vaultId, String captureId) {
        return jdbc.queryForList("""
            SELECT path FROM notes
            WHERE vault_id = ? AND capture_id = ?
            ORDER BY capture_seq ASC NULLS LAST, path ASC
            """, String.class, vaultId, captureId);
    }

    public record InboxNoteRow(String captureId, String path, Integer captureSeq) {}

    /** Every indexed note still under _inbox/ that carries a capture-id (staged ingest notes). */
    public List<InboxNoteRow> listInboxCaptureNotes(long vaultId) {
        return jdbc.query("""
            SELECT capture_id, path, capture_seq FROM notes
            WHERE vault_id = ? AND capture_id IS NOT NULL AND path LIKE '%/_inbox/%'
            """, (rs, i) -> new InboxNoteRow(rs.getString("capture_id"), rs.getString("path"),
                (Integer) rs.getObject("capture_seq")), vaultId);
    }

    /** How many of a capture's notes are still parked in _inbox/ awaiting triage.
     *  When this hits zero the capture is fully filed (InboxController flips status). */
    public int countUnfiledNotesForCapture(long vaultId, String captureId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notes WHERE vault_id = ? AND capture_id = ? AND path LIKE '%/_inbox/%'",
            Integer.class, vaultId, captureId);
        return n == null ? 0 : n;
    }

    /** Notes whose text changed since they were last embedded (or never were).
     *  Returns path → content_hash at selection time. */
    public Map<String, String> findNotesNeedingEmbedding(long vaultId, int limit) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT path, content_hash FROM notes
            WHERE vault_id = ?
              AND content_hash IS NOT NULL
              AND content_hash IS DISTINCT FROM embedded_hash
              AND ingest_pending = false
            ORDER BY modified_at DESC
            LIMIT ?
            """, rs -> { out.put(rs.getString("path"), rs.getString("content_hash")); }, vaultId, limit);
        return out;
    }

    /** Single-note version of {@link #findNotesNeedingEmbedding}'s predicate — used
     *  by the outbox producer at the registerImages chokepoint to decide whether the
     *  note just written is worth an "embed" message, without a table scan. */
    public boolean needsEmbedding(long vaultId, String path) {
        Boolean needs = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM notes
                WHERE vault_id = ? AND path = ?
                  AND content_hash IS NOT NULL
                  AND content_hash IS DISTINCT FROM embedded_hash
                  AND ingest_pending = false
            )
            """, Boolean.class, vaultId, path);
        return Boolean.TRUE.equals(needs);
    }

    /** Records that {@code indexedHash} is fully embedded. Guarded so a note
     *  edited mid-indexing stays in the work list (hash no longer matches). */
    public void markEmbedded(long vaultId, String path, String indexedHash) {
        jdbc.update(
            "UPDATE notes SET embedded_hash = ? WHERE vault_id = ? AND path = ? AND content_hash = ?",
            indexedHash, vaultId, path, indexedHash);
    }

    public void syncWithDisk(long vaultId, List<File> diskFiles) {
        Map<String, File> diskMap = new HashMap<>();
        for (File f : diskFiles) diskMap.put(f.getAbsolutePath(), f);

        Map<String, Long> dbMap = new HashMap<>();
        jdbc.query("SELECT path, modified_at FROM notes WHERE vault_id = ?",
            (RowCallbackHandler) rs -> dbMap.put(rs.getString("path"), rs.getLong("modified_at")), vaultId);

        int inserted = 0, updated = 0, deleted = 0;

        for (Map.Entry<String, File> entry : diskMap.entrySet()) {
            String path   = entry.getKey();
            File   file   = entry.getValue();
            long   diskMod = file.lastModified();
            Long   dbMod   = dbMap.get(path);

            if (dbMod == null || diskMod > dbMod) {
                try {
                    String content = Files.readString(file.toPath());
                    FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(content);
                    String title = file.getName().replace(".md", "");
                    upsert(vaultId, path, title, meta, diskMod);
                    noteLinkRepo.updateLinks(vaultId, path, NoteLinkRepository.extractTargets(content));
                    if (dbMod == null) inserted++; else updated++;
                } catch (IOException e) {
                    log.warn("[syncWithDisk] skip {}: {}", path, e.getMessage());
                }
            }
        }

        for (String dbPath : dbMap.keySet()) {
            if (!diskMap.containsKey(dbPath)) {
                delete(vaultId, dbPath);
                noteLinkRepo.deleteSource(vaultId, dbPath);
                deleted++;
            }
        }

        log.info("[syncWithDisk] +{} ~{} -{} (disk={} db={})",
            inserted, updated, deleted, diskMap.size(), dbMap.size());
    }

    public ArrayList<String> getAllPaths(long vaultId) {
        return new ArrayList<>(
            jdbc.queryForList("SELECT path FROM notes WHERE vault_id = ? ORDER BY path", String.class, vaultId));
    }

    public FileRepository.ReviewPage getReviewNotesPaged(long vaultId, int offset, int limit) {
        // Exclude the ingest staging folder (_inbox): those notes are awaiting
        // triage in the Learn Inbox and must not enter the FSRS review queue until
        // the user files them to a real folder.
        List<String> rows = jdbc.queryForList("""
            SELECT path FROM notes
            WHERE vault_id = ? AND sr_due <= CURRENT_DATE
              AND path NOT LIKE '%/_inbox/%'
            ORDER BY sr_due ASC, path ASC
            LIMIT ? OFFSET ?
            """, String.class, vaultId, limit + 1, offset);

        boolean hasMore = rows.size() > limit;
        List<String> page = hasMore ? rows.subList(0, limit) : rows;
        return new FileRepository.ReviewPage(new ArrayList<>(page), hasMore);
    }

    /** Due query enriched with a hasCards flag per note (EXISTS against active cards),
     *  same ordering/exclusions as getReviewNotesPaged. Feeds the hybrid-review split. */
    public FileRepository.ReviewPageInfo getReviewNotesPagedWithCards(long vaultId, int offset, int limit) {
        List<FileRepository.ReviewNoteInfo> rows = jdbc.query("""
            SELECT n.path AS path,
                   EXISTS (SELECT 1 FROM cards c
                           WHERE c.note_path = n.path AND c.vault_id = n.vault_id
                             AND c.status = 'ACTIVE') AS has_cards
            FROM notes n
            WHERE n.vault_id = ? AND n.sr_due <= CURRENT_DATE
              AND n.path NOT LIKE '%/_inbox/%'
            ORDER BY n.sr_due ASC, n.path ASC
            LIMIT ? OFFSET ?
            """,
            (rs, i) -> new FileRepository.ReviewNoteInfo(rs.getString("path"), rs.getBoolean("has_cards")),
            vaultId, limit + 1, offset);

        boolean hasMore = rows.size() > limit;
        List<FileRepository.ReviewNoteInfo> page = hasMore ? rows.subList(0, limit) : rows;
        return new FileRepository.ReviewPageInfo(new ArrayList<>(page), hasMore);
    }

    public void upsert(long vaultId, String path, String title, FrontmatterParser.NoteMetadata meta, long modifiedAt) {
        jdbc.update("""
            INSERT INTO notes(path, title, sr_due, sr_interval, sr_ease, modified_at,
                              capture_id, capture_seq, vault_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (path, vault_id) DO UPDATE SET
              title       = EXCLUDED.title,
              sr_due      = EXCLUDED.sr_due,
              sr_interval = EXCLUDED.sr_interval,
              sr_ease     = EXCLUDED.sr_ease,
              modified_at = EXCLUDED.modified_at,
              capture_id  = EXCLUDED.capture_id,
              capture_seq = EXCLUDED.capture_seq
            """,
            path,
            title,
            meta.srDue() != null ? Date.valueOf(meta.srDue()) : null,
            meta.srInterval(),
            meta.srEase(),
            modifiedAt,
            meta.captureId(),
            meta.captureSeq(),
            vaultId);
    }

    public void rename(long vaultId, String oldPath, String newPath, String newTitle) {
        jdbc.update(
            "UPDATE notes SET path = ?, title = ? WHERE path = ? AND vault_id = ?",
            newPath, newTitle, oldPath, vaultId);
    }

    public void delete(long vaultId, String path) {
        jdbc.update("DELETE FROM notes WHERE path = ? AND vault_id = ?", path, vaultId);
    }

    /** Updates both hashes in one write at the registerImages chokepoint.
     *  content_hash = full file (Drive sync); body_hash = frontmatter-stripped
     *  (card work-list key, so sr-due/chrono frontmatter edits don't re-card). */
    public void updateContentHash(long vaultId, String path, String contentHash, String bodyHash) {
        jdbc.update("UPDATE notes SET content_hash = ?, body_hash = ? WHERE path = ? AND vault_id = ?",
            contentHash, bodyHash, path, vaultId);
    }

    /** Readiness gate flag — true while the note has an un-ingested resource embed.
     *  Maintained by ResourceScanService on every write; read by the embedding and
     *  card worklists to skip notes whose content isn't finalized yet. */
    public void setIngestPending(long vaultId, String path, boolean pending) {
        jdbc.update("UPDATE notes SET ingest_pending = ? WHERE path = ? AND vault_id = ?", pending, path, vaultId);
    }

    /** Notes still waiting on ingest (the retry worker re-fires these). Random order
     *  so a large backlog rotates fairly instead of starving the tail; the embedder
     *  de-dups, so re-firing an already-queued note is harmless. */
    public List<String> findIngestPending(long vaultId, int limit) {
        return jdbc.queryForList(
            "SELECT path FROM notes WHERE vault_id = ? AND ingest_pending = true ORDER BY random() LIMIT ?",
            String.class, vaultId, limit);
    }

    public String getContentHash(long vaultId, String path) {
        List<String> rows = jdbc.queryForList(
            "SELECT content_hash FROM notes WHERE vault_id = ? AND path = ?", String.class, vaultId, path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Frontmatter-stripped hash — the key the card worker diffs on (see findNotesNeedingCards). */
    public String getBodyHash(long vaultId, String path) {
        List<String> rows = jdbc.queryForList(
            "SELECT body_hash FROM notes WHERE vault_id = ? AND path = ?", String.class, vaultId, path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> getAllPathsWithHash(long vaultId) {
        return jdbc.queryForList("SELECT path, content_hash FROM notes WHERE vault_id = ? AND content_hash IS NOT NULL", vaultId);
    }
}
