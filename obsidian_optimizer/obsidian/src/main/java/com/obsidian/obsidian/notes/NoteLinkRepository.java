package com.obsidian.obsidian.notes;

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

    public void updateLinks(String sourcePath, Set<String> targets) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ?", sourcePath);
        for (String target : targets) {
            jdbc.update(
                "INSERT INTO note_links(source_path, target_name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                sourcePath, target);
        }
    }

    public List<String> findSourcesByTarget(String targetName) {
        return jdbc.queryForList(
            "SELECT source_path FROM note_links WHERE target_name = ?",
            String.class, targetName);
    }

    public void renameTarget(String oldName, String newName) {
        jdbc.update(
            "UPDATE note_links SET target_name = ? WHERE target_name = ?",
            newName, oldName);
    }

    public void renameSource(String oldPath, String newPath) {
        jdbc.update(
            "UPDATE note_links SET source_path = ? WHERE source_path = ?",
            newPath, oldPath);
    }

    public void deleteSource(String sourcePath) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ?", sourcePath);
    }

    public void backfillIfEmpty(List<String> notePaths) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM note_links", Integer.class);
        if (count != null && count > 0) return;
        backfill(notePaths);
    }

    public void truncateLinks() {
        jdbc.execute("TRUNCATE note_links");
    }

    public void forceRebuildLinks(List<String> notePaths) {
        jdbc.execute("TRUNCATE note_links");
        backfill(notePaths);
    }

    private void backfill(List<String> notePaths) {
        for (String path : notePaths) {
            try {
                String content = java.nio.file.Files.readString(java.nio.file.Paths.get(path));
                updateLinks(path, extractTargets(content));
            } catch (java.io.IOException e) {
                System.err.println("backfill skip " + path + ": " + e.getMessage());
            }
        }
    }

    public static Set<String> extractTargets(String markdown) {
        Set<String> targets = new HashSet<>();
        Matcher m = WIKI_LINK.matcher(markdown);
        while (m.find()) {
            String raw = m.group(1).trim();
            int slash = raw.lastIndexOf('/');
            String name = slash >= 0 ? raw.substring(slash + 1) : raw;
            // [[Note#heading]] targets Note — strip the anchor so backlink
            // lookups during rename find these sources too.
            int hash = name.indexOf('#');
            if (hash >= 0) name = name.substring(0, hash).trim();
            if (!name.isBlank()) targets.add(name);
        }
        return targets;
    }

    public static String rewriteLinks(String content, String oldName, String newName) {
        // group 3: optional #heading anchor — preserved through the rename
        Pattern p = Pattern.compile(
            "\\[\\[([^|\\]]*/)?(" + Pattern.quote(oldName) + ")(#[^|\\]]*)?([|\\]])");
        return p.matcher(content).replaceAll(mr -> {
            String pathPrefix = mr.group(1) != null ? mr.group(1) : "";
            String heading    = mr.group(3) != null ? mr.group(3) : "";
            return "[[" + pathPrefix + newName + heading + mr.group(4);
        });
    }
}
