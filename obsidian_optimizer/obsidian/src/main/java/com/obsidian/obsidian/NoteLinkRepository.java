package com.obsidian.obsidian;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NoteLinkRepository {

    private final JdbcTemplate jdbc;

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|[^\\]]+)?\\]\\]");

    public NoteLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS note_links (
                source_path TEXT NOT NULL,
                target_name TEXT NOT NULL,
                PRIMARY KEY (source_path, target_name)
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_note_links_target ON note_links(target_name)");
    }

    // Replace all outgoing links for a note (call after every write).
    public void updateLinks(String sourcePath, Set<String> targets) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ?", sourcePath);
        for (String target : targets) {
            jdbc.update(
                "INSERT INTO note_links(source_path, target_name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                sourcePath, target);
        }
    }

    // Returns paths of all notes containing [[oldName]] or [[.../oldName]].
    public List<String> findSourcesByTarget(String targetName) {
        return jdbc.queryForList(
            "SELECT source_path FROM note_links WHERE target_name = ?",
            String.class, targetName);
    }

    // Bulk-rename all target entries (used when a note is renamed).
    public void renameTarget(String oldName, String newName) {
        jdbc.update(
            "UPDATE note_links SET target_name = ? WHERE target_name = ?",
            newName, oldName);
    }

    // Update the source_path for a renamed note's own outgoing link entries.
    public void renameSource(String oldPath, String newPath) {
        jdbc.update(
            "UPDATE note_links SET source_path = ? WHERE source_path = ?",
            newPath, oldPath);
    }

    // Remove all outgoing links for a deleted note.
    public void deleteSource(String sourcePath) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ?", sourcePath);
    }

    // Seed the table from an existing note list on first startup.
    // notePaths comes from FileRepository.getNoteNames() to avoid circular dep.
    public void backfillIfEmpty(List<String> notePaths) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM note_links", Integer.class);
        if (count != null && count > 0) return;

        for (String path : notePaths) {
            try {
                String content = java.nio.file.Files.readString(java.nio.file.Paths.get(path));
                updateLinks(path, extractTargets(content));
            } catch (java.io.IOException e) {
                System.err.println("backfill skip " + path + ": " + e.getMessage());
            }
        }
    }

    // ── Static helpers ───────────────────────────────────────────────────────

    // Extract all wiki-link basenames from markdown content.
    public static Set<String> extractTargets(String markdown) {
        Set<String> targets = new HashSet<>();
        Matcher m = WIKI_LINK.matcher(markdown);
        while (m.find()) {
            String raw = m.group(1).trim();
            int slash = raw.lastIndexOf('/');
            String name = slash >= 0 ? raw.substring(slash + 1) : raw;
            if (!name.isBlank()) targets.add(name);
        }
        return targets;
    }

    // Rewrite [[oldName]] / [[Folder/oldName]] / [[oldName|text]] in content.
    public static String rewriteLinks(String content, String oldName, String newName) {
        Pattern p = Pattern.compile(
            "\\[\\[([^|\\]]*/)?(" + Pattern.quote(oldName) + ")([|\\]])");
        return p.matcher(content).replaceAll(mr -> {
            String pathPrefix = mr.group(1) != null ? mr.group(1) : "";
            return "[[" + pathPrefix + newName + mr.group(3);
        });
    }
}
