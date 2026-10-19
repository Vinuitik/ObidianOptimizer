package com.obsidian.obsidian.notes;

import com.obsidian.obsidian.vaults.VaultKeys;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("vaultRepository")
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
        jdbc.execute("ALTER TABLE note_links ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        VaultKeys.swapPrimaryKey(jdbc, "note_links", "source_path, target_name");
    }

    public void updateLinks(long vaultId, String sourcePath, Set<String> targets) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ? AND vault_id = ?", sourcePath, vaultId);
        for (String target : targets) {
            jdbc.update(
                "INSERT INTO note_links(source_path, target_name, vault_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                sourcePath, target, vaultId);
        }
    }

    public List<String> findSourcesByTarget(long vaultId, String targetName) {
        return jdbc.queryForList(
            "SELECT source_path FROM note_links WHERE vault_id = ? AND target_name = ?",
            String.class, vaultId, targetName);
    }

    public void renameTarget(long vaultId, String oldName, String newName) {
        jdbc.update(
            "UPDATE note_links SET target_name = ? WHERE target_name = ? AND vault_id = ?",
            newName, oldName, vaultId);
    }

    public void renameSource(long vaultId, String oldPath, String newPath) {
        jdbc.update(
            "UPDATE note_links SET source_path = ? WHERE source_path = ? AND vault_id = ?",
            newPath, oldPath, vaultId);
    }

    public void deleteSource(long vaultId, String sourcePath) {
        jdbc.update("DELETE FROM note_links WHERE source_path = ? AND vault_id = ?", sourcePath, vaultId);
    }

    public void backfillIfEmpty(long vaultId, List<String> notePaths) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM note_links WHERE vault_id = ?", Integer.class, vaultId);
        if (count != null && count > 0) return;
        backfill(vaultId, notePaths);
    }

    public void truncateLinks(long vaultId) {
        jdbc.update("DELETE FROM note_links WHERE vault_id = ?", vaultId);
    }

    public void forceRebuildLinks(long vaultId, List<String> notePaths) {
        truncateLinks(vaultId);
        backfill(vaultId, notePaths);
    }

    private void backfill(long vaultId, List<String> notePaths) {
        for (String path : notePaths) {
            try {
                String content = java.nio.file.Files.readString(java.nio.file.Paths.get(path));
                updateLinks(vaultId, path, extractTargets(content));
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
