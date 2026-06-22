package com.obsidian.obsidian.notes;

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
import org.springframework.stereotype.Component;

@Component
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
    }

    /** Notes whose text changed since they were last embedded (or never were).
     *  Returns path → content_hash at selection time. */
    public Map<String, String> findNotesNeedingEmbedding(int limit) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT path, content_hash FROM notes
            WHERE content_hash IS NOT NULL
              AND content_hash IS DISTINCT FROM embedded_hash
              AND ingest_pending = false
            ORDER BY modified_at DESC
            LIMIT ?
            """, rs -> { out.put(rs.getString("path"), rs.getString("content_hash")); }, limit);
        return out;
    }

    /** Records that {@code indexedHash} is fully embedded. Guarded so a note
     *  edited mid-indexing stays in the work list (hash no longer matches). */
    public void markEmbedded(String path, String indexedHash) {
        jdbc.update(
            "UPDATE notes SET embedded_hash = ? WHERE path = ? AND content_hash = ?",
            indexedHash, path, indexedHash);
    }

    public void syncWithDisk(List<File> diskFiles) {
        Map<String, File> diskMap = new HashMap<>();
        for (File f : diskFiles) diskMap.put(f.getAbsolutePath(), f);

        Map<String, Long> dbMap = new HashMap<>();
        jdbc.query("SELECT path, modified_at FROM notes",
            (RowCallbackHandler) rs -> dbMap.put(rs.getString("path"), rs.getLong("modified_at")));

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
                    upsert(path, title, meta, diskMod);
                    noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(content));
                    if (dbMod == null) inserted++; else updated++;
                } catch (IOException e) {
                    log.warn("[syncWithDisk] skip {}: {}", path, e.getMessage());
                }
            }
        }

        for (String dbPath : dbMap.keySet()) {
            if (!diskMap.containsKey(dbPath)) {
                delete(dbPath);
                noteLinkRepo.deleteSource(dbPath);
                deleted++;
            }
        }

        log.info("[syncWithDisk] +{} ~{} -{} (disk={} db={})",
            inserted, updated, deleted, diskMap.size(), dbMap.size());
    }

    public void forceResync(List<File> diskFiles) {
        jdbc.execute("TRUNCATE notes");
        noteLinkRepo.truncateLinks();
        syncWithDisk(diskFiles);
    }

    public ArrayList<String> getAllPaths() {
        return new ArrayList<>(
            jdbc.queryForList("SELECT path FROM notes ORDER BY path", String.class));
    }

    public FileRepository.ReviewPage getReviewNotesPaged(int offset, int limit) {
        // Exclude the ingest staging folder (_inbox): those notes are awaiting
        // triage in the Learn Inbox and must not enter the FSRS review queue until
        // the user files them to a real folder.
        List<String> rows = jdbc.queryForList("""
            SELECT path FROM notes
            WHERE sr_due <= CURRENT_DATE
              AND path NOT LIKE '%/_inbox/%'
            ORDER BY sr_due ASC, path ASC
            LIMIT ? OFFSET ?
            """, String.class, limit + 1, offset);

        boolean hasMore = rows.size() > limit;
        List<String> page = hasMore ? rows.subList(0, limit) : rows;
        return new FileRepository.ReviewPage(new ArrayList<>(page), hasMore);
    }

    public void upsert(String path, String title, FrontmatterParser.NoteMetadata meta, long modifiedAt) {
        jdbc.update("""
            INSERT INTO notes(path, title, sr_due, sr_interval, sr_ease, modified_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (path) DO UPDATE SET
              title       = EXCLUDED.title,
              sr_due      = EXCLUDED.sr_due,
              sr_interval = EXCLUDED.sr_interval,
              sr_ease     = EXCLUDED.sr_ease,
              modified_at = EXCLUDED.modified_at
            """,
            path,
            title,
            meta.srDue() != null ? Date.valueOf(meta.srDue()) : null,
            meta.srInterval(),
            meta.srEase(),
            modifiedAt);
    }

    public void rename(String oldPath, String newPath, String newTitle) {
        jdbc.update(
            "UPDATE notes SET path = ?, title = ? WHERE path = ?",
            newPath, newTitle, oldPath);
    }

    public void delete(String path) {
        jdbc.update("DELETE FROM notes WHERE path = ?", path);
    }

    /** Updates both hashes in one write at the registerImages chokepoint.
     *  content_hash = full file (Drive sync); body_hash = frontmatter-stripped
     *  (card work-list key, so sr-due/chrono frontmatter edits don't re-card). */
    public void updateContentHash(String path, String contentHash, String bodyHash) {
        jdbc.update("UPDATE notes SET content_hash = ?, body_hash = ? WHERE path = ?",
            contentHash, bodyHash, path);
    }

    /** Readiness gate flag — true while the note has an un-ingested resource embed.
     *  Maintained by ResourceScanService on every write; read by the embedding and
     *  card worklists to skip notes whose content isn't finalized yet. */
    public void setIngestPending(String path, boolean pending) {
        jdbc.update("UPDATE notes SET ingest_pending = ? WHERE path = ?", pending, path);
    }

    /** Notes still waiting on ingest (the retry worker re-fires these). Random order
     *  so a large backlog rotates fairly instead of starving the tail; the embedder
     *  de-dups, so re-firing an already-queued note is harmless. */
    public List<String> findIngestPending(int limit) {
        return jdbc.queryForList(
            "SELECT path FROM notes WHERE ingest_pending = true ORDER BY random() LIMIT ?",
            String.class, limit);
    }

    public String getContentHash(String path) {
        List<String> rows = jdbc.queryForList(
            "SELECT content_hash FROM notes WHERE path = ?", String.class, path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Frontmatter-stripped hash — the key the card worker diffs on (see findNotesNeedingCards). */
    public String getBodyHash(String path) {
        List<String> rows = jdbc.queryForList(
            "SELECT body_hash FROM notes WHERE path = ?", String.class, path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> getAllPathsWithHash() {
        return jdbc.queryForList("SELECT path, content_hash FROM notes WHERE content_hash IS NOT NULL");
    }
}
