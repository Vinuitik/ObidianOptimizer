package com.obsidian.obsidian.inbox;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One-time, idempotent cleanup of the {@code _inbox} layout (active vault only):
 * <ol>
 *   <li>{@code _inbox/<captureId>/…} folders are renamed to the source's
 *       {@code ingest-source-title} (same sanitize + " 2"/" 3" collision rule as the embedder's
 *       publish.unit_folder_name). Notes move one by one through {@link FileRepository#moveNote}
 *       so note_index, links and the sync queue stay consistent.</li>
 *   <li>Notes in a named {@code _inbox/<folder>/} with no capture-id (older MCP create_note
 *       batches) get the synthetic id {@code mcp-<sha1(vaultId:folder lower)[:8]>} (same as
 *       publish.mcp_capture_id) via {@link FileRepository#updateNote}, so the folder becomes one unit.</li>
 * </ol>
 * Loose notes directly in {@code _inbox/} are left alone. Re-running changes nothing.
 */
@Service
public class InboxFolderMigration {

    private static final Logger log = LoggerFactory.getLogger(InboxFolderMigration.class);
    private static final Pattern BAD = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    private final FileRepository files;
    private final ActiveVault activeVault;

    public InboxFolderMigration(FileRepository files, ActiveVault activeVault) {
        this.files = files;
        this.activeVault = activeVault;
    }

    public record Report(List<String> renamed, List<String> stamped, List<String> skipped) {}

    /** Mirrors publish.sanitize_unit_name. */
    static String sanitize(String title) {
        String s = BAD.matcher(title == null ? "" : title).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.replaceAll("^[ ._]+|[ ._]+$", "");
    }

    /** Mirrors publish.mcp_capture_id. */
    static String mcpCaptureId(long vaultId, String folder) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                .digest((vaultId + ":" + folder.strip().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("mcp-");
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Report run(boolean dryRun) throws IOException {
        Report rep = new Report(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Path inbox = activeVault.root().resolve("_inbox");
        if (!Files.isDirectory(inbox)) return rep;
        List<Path> dirs;
        try (Stream<Path> st = Files.list(inbox)) {
            dirs = st.filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().startsWith("_"))
                .sorted().toList();
        }
        for (Path dir : dirs) {
            try {
                handle(inbox, dir, dryRun, rep);
            } catch (IOException | RuntimeException e) {
                log.warn("[inboxMigration] {} failed: {}", dir, e.getMessage());
                rep.skipped().add(dir.getFileName() + ": " + e.getMessage());
            }
        }
        return rep;
    }

    private void handle(Path inbox, Path dir, boolean dryRun, Report rep) throws IOException {
        List<Path> notes = mdFiles(dir);
        if (notes.isEmpty()) return;
        String name = dir.getFileName().toString();
        Map<Path, String> content = new LinkedHashMap<>();
        for (Path n : notes) content.put(n, Files.readString(n));

        boolean hashNamed = content.values().stream()
            .allMatch(c -> name.equals(fm(c, "capture-id")));
        if (hashNamed) {
            String title = content.values().stream().map(c -> fm(c, "ingest-source-title"))
                .map(InboxFolderMigration::sanitize).filter(s -> !s.isEmpty()).findFirst().orElse("");
            if (title.isEmpty()) { rep.skipped().add(name + ": no ingest-source-title"); return; }
            Path target = pickTarget(inbox, title, name);
            rep.renamed().add(name + " -> " + target.getFileName());
            if (dryRun) return;
            for (Path n : notes) {
                Path rel = dir.relativize(n).getParent();
                Path destDir = rel == null ? target : target.resolve(rel);
                Files.createDirectories(destDir);
                files.moveNote(n.toAbsolutePath().toString(), destDir.toAbsolutePath().toString());
            }
            pruneEmpty(dir);
            return;
        }

        // Named folder: stamp notes lacking a capture-id.
        List<Path> missing = notes.stream().filter(n -> fm(content.get(n), "capture-id").isEmpty())
            .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
        if (missing.isEmpty()) return;
        List<String> existing = content.values().stream().map(c -> fm(c, "capture-id"))
            .filter(s -> !s.isEmpty()).distinct().toList();
        String cid = existing.size() == 1 ? existing.get(0) : mcpCaptureId(activeVault.id(), name);
        int seq = notes.size() - missing.size();
        for (Path n : missing) {
            rep.stamped().add(name + "/" + n.getFileName() + " -> " + cid);
            if (!dryRun) {
                files.updateNote(activeVault.id(), n.toAbsolutePath().toString(),
                    stamp(content.get(n), cid, seq));
            }
            seq++;
        }
    }

    /** First free candidate: absent, empty of notes, or already holding only {@code captureId}. */
    private Path pickTarget(Path inbox, String base, String captureId) throws IOException {
        for (int n = 1; ; n++) {
            Path cand = inbox.resolve(n == 1 ? base : base + " " + n);
            if (!Files.isDirectory(cand)) return cand;
            List<String> ids = new ArrayList<>();
            for (Path p : mdFiles(cand)) {
                String id = fm(Files.readString(p), "capture-id");
                if (!ids.contains(id)) ids.add(id);
            }
            if (ids.isEmpty() || (ids.size() == 1 && ids.get(0).equals(captureId))) return cand;
        }
    }

    static String stamp(String c, String cid, int seq) {
        String extra = "capture-id: " + cid + "\ncapture-seq: " + seq + "\n";
        if (c.startsWith("---\n")) {
            int end = c.indexOf("\n---\n", 4);
            if (end >= 0) return c.substring(0, end + 1) + extra + c.substring(end + 1);
        }
        return "---\n" + extra + "---\n\n" + c;
    }

    private static List<Path> mdFiles(Path dir) throws IOException {
        try (Stream<Path> st = Files.walk(dir)) {
            return st.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static void pruneEmpty(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> st = Files.walk(dir)) {
            all = st.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path d : all) {
            try (Stream<Path> kids = Files.list(d)) {
                if (kids.findAny().isEmpty()) Files.delete(d);
            }
        }
    }

    static String fm(String content, String key) {
        if (!content.startsWith("---\n")) return "";
        int end = content.indexOf("\n---\n", 4);
        if (end < 0) return "";
        for (String line : content.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(key)) return line.substring(colon + 1).trim();
        }
        return "";
    }
}
