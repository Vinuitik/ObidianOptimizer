package com.obsidian.obsidian.notes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
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
        List<String> rows = jdbc.queryForList("""
            SELECT path FROM notes
            WHERE sr_due <= CURRENT_DATE
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
}
