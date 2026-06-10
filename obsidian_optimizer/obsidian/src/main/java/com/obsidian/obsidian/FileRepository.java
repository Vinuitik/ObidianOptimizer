package com.obsidian.obsidian;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FileRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRepository.class);

    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", ".obsidian", "_trash", "resources");

    private String ROOT_FILE;

    private final NoteLinkRepository noteLinkRepo;
    private final SettingsRepository settingsRepo;
    private final NoteIndexRepository noteIndex;

    public FileRepository(NoteLinkRepository noteLinkRepo,
                          SettingsRepository settingsRepo,
                          NoteIndexRepository noteIndex) {
        this.noteLinkRepo = noteLinkRepo;
        this.settingsRepo = settingsRepo;
        this.noteIndex    = noteIndex;
    }

    @PostConstruct
    public void init() {
        ROOT_FILE = settingsRepo.getVaultPath();
        List<File> diskFiles = bfsDiskFiles();

        String syncMode = settingsRepo.getStartupSyncMode();
        if ("async".equals(syncMode)) {
            log.info("[init] async sync started for {}", ROOT_FILE);
            CompletableFuture.runAsync(() -> noteIndex.syncWithDisk(diskFiles));
        } else {
            log.info("[init] blocking sync started for {}", ROOT_FILE);
            noteIndex.syncWithDisk(diskFiles);
            log.info("[init] blocking sync complete");
        }
    }

    // ── Vault path change ────────────────────────────────────────────────────

    public void updateVaultPath(String newPath) throws IOException {
        File dir = new File(newPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException("Directory not found: " + newPath);
        }
        settingsRepo.set("vaultPath", newPath);
        ROOT_FILE = newPath;
        // Force a full resync: wipes both notes + note_links, then repopulates
        noteIndex.forceResync(bfsDiskFiles());
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    // Returns all note paths from the DB index (O(1) query, no disk walk).
    public ArrayList<String> getNoteNames() {
        return noteIndex.getAllPaths();
    }

    public String getText(String path) {
        try {
            String content = Files.readString(Paths.get(path));
            log.debug("[getText] read {} bytes from {}", content.length(), path);
            return content;
        } catch (IOException e) {
            log.error("[getText] failed to read path={}: {}", path, e.getMessage());
            return "";
        }
    }

    public String getRootPath() {
        return new File(ROOT_FILE).getAbsolutePath();
    }

    // Returns immediate children (one level) of a folder — no recursion.
    public ChildrenResult getDirectChildren(String folderPath) {
        File dir = new File(folderPath);
        List<String> folderPaths = new ArrayList<>();
        List<String> filePaths   = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                String n = child.getName();
                if (child.isDirectory()) {
                    if (!EXCLUDED_DIRS.contains(n)) {
                        folderPaths.add(child.getAbsolutePath());
                    }
                } else if (child.isFile() && n.endsWith(".md")) {
                    filePaths.add(child.getAbsolutePath());
                }
            }
        }

        Collections.sort(folderPaths);
        Collections.sort(filePaths);
        return new ChildrenResult(dir.getAbsolutePath(), folderPaths, filePaths);
    }

    // Returns paginated review-due notes from the DB index.
    public ReviewPage getReviewNotesPaged(int offset, int limit) {
        return noteIndex.getReviewNotesPaged(offset, limit);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public String createFolder(String parentPath, String name) throws IOException {
        File parent = new File(parentPath);
        if (!parent.exists() || !parent.isDirectory()) {
            throw new IOException("Parent folder not found: " + parentPath);
        }
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            throw new IOException("Invalid folder name: " + name);
        }
        File newDir = new File(parent, name);
        if (newDir.exists()) {
            throw new IOException("Folder already exists: " + name);
        }
        if (!newDir.mkdir()) {
            throw new IOException("Failed to create folder: " + name);
        }
        return newDir.getAbsolutePath();
    }

    public String createNote(String folderPath, String name) throws IOException {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            throw new IOException("Folder not found: " + folderPath);
        }

        String filename = name.endsWith(".md") ? name : name + ".md";
        File noteFile = new File(folder, filename);

        if (noteFile.exists()) {
            throw new IOException("Note already exists: " + filename);
        }

        String srDue = LocalDate.now().plusDays(3).toString();
        String initialContent = "---\nsr-due: " + srDue + "\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n";
        Files.writeString(noteFile.toPath(), initialContent);

        String path = noteFile.getAbsolutePath();
        FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(initialContent);
        noteIndex.upsert(path, name.replace(".md", ""), meta, noteFile.lastModified());
        noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(initialContent));
        return path;
    }

    public void updateNote(String path, String content) throws IOException {
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);
        Files.writeString(Paths.get(path), content);

        String title = file.getName().replace(".md", "");
        FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(content);
        noteIndex.upsert(path, title, meta, file.lastModified());
        noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(content));
    }

    public void patchNote(String path, List<PatchHunk> hunks) throws IOException {
        log.info("[patchNote] applying {} hunks to {}", hunks == null ? 0 : hunks.size(), path);
        if (hunks == null || hunks.isEmpty()) return;
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);

        String original = Files.readString(Paths.get(path));
        String sep = original.contains("\r\n") ? "\r\n" : "\n";

        List<String> lines = new ArrayList<>(
            Arrays.asList(original.replace("\r\n", "\n").split("\n", -1)));

        List<PatchHunk> sorted = new ArrayList<>(hunks);
        sorted.sort(Comparator.comparingInt(PatchHunk::startLine).reversed());

        for (PatchHunk h : sorted) {
            int start = h.startLine();
            if (start < 0 || start > lines.size()) {
                throw new IOException("Patch hunk out of range: startLine=" + start + " fileLines=" + lines.size());
            }
            for (int i = 0; i < h.deleteCount(); i++) {
                if (start < lines.size()) lines.remove(start);
            }
            if (h.insertLines() != null) {
                lines.addAll(start, h.insertLines());
            }
        }

        String newContent = String.join(sep, lines);
        Files.writeString(Paths.get(path), newContent);

        String title = file.getName().replace(".md", "");
        FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(newContent);
        noteIndex.upsert(path, title, meta, file.lastModified());
        noteLinkRepo.updateLinks(path, NoteLinkRepository.extractTargets(newContent));
    }

    public String renameNote(String oldPath, String newName) throws IOException {
        File oldFile = new File(oldPath);
        if (!oldFile.exists()) throw new IOException("Note not found: " + oldPath);

        String oldName = oldFile.getName().replace(".md", "");
        String filename = newName.endsWith(".md") ? newName : newName + ".md";
        File newFile = new File(oldFile.getParent(), filename);

        if (newFile.exists() && !newFile.getAbsolutePath().equals(oldFile.getAbsolutePath())) {
            throw new IOException("A note named '" + newName + "' already exists in this folder");
        }

        List<String> sources = noteLinkRepo.findSourcesByTarget(oldName);

        if (!oldFile.renameTo(newFile)) {
            throw new IOException("Rename failed");
        }

        String newPath = newFile.getAbsolutePath();

        for (String sourcePath : sources) {
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) continue;
            try {
                String content = Files.readString(sourceFile.toPath());
                String updated = NoteLinkRepository.rewriteLinks(content, oldName, newName);
                if (!updated.equals(content)) {
                    Files.writeString(sourceFile.toPath(), updated);
                }
            } catch (IOException e) {
                log.warn("[renameNote] failed to rewrite links in {}: {}", sourcePath, e.getMessage());
            }
        }

        noteIndex.rename(oldPath, newPath, newName);
        noteLinkRepo.renameTarget(oldName, newName);
        noteLinkRepo.renameSource(oldPath, newPath);
        return newPath;
    }

    public String moveNote(String sourcePath, String targetFolder) throws IOException {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) throw new IOException("Note not found: " + sourcePath);

        File targetDir = new File(targetFolder);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            throw new IOException("Target folder not found: " + targetFolder);
        }

        File destFile = new File(targetDir, sourceFile.getName());
        if (destFile.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
            return sourcePath; // already in target folder — no-op
        }
        if (destFile.exists()) {
            throw new IOException("A note named '" + sourceFile.getName() + "' already exists in the target folder");
        }

        Path newPath = Files.move(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        String newAbsPath = newPath.toFile().getAbsolutePath();
        String title = sourceFile.getName().replace(".md", "");

        noteIndex.rename(sourcePath, newAbsPath, title);
        noteLinkRepo.renameSource(sourcePath, newAbsPath);
        return newAbsPath;
    }

    public void softDeleteNote(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);

        File trashDir = new File(ROOT_FILE, "_trash");
        if (!trashDir.exists()) trashDir.mkdirs();

        File dest = new File(trashDir, file.getName());
        if (dest.exists()) {
            String base = file.getName().replace(".md", "");
            dest = new File(trashDir, base + "_" + System.currentTimeMillis() + ".md");
        }

        if (!file.renameTo(dest)) throw new IOException("Failed to move note to trash");

        noteIndex.delete(path);
        noteLinkRepo.deleteSource(path);
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record ChildrenResult(String parentPath, List<String> folderPaths, List<String> filePaths) {}
    public record ReviewPage(List<String> notes, boolean hasMore) {}
    public record PatchHunk(int startLine, int deleteCount, List<String> insertLines) {}

    // ── Private ───────────────────────────────────────────────────────────────

    // Raw BFS disk walk — used only by init() and updateVaultPath() to feed syncWithDisk.
    private List<File> bfsDiskFiles() {
        List<File> files = new ArrayList<>();
        File root = new File(ROOT_FILE);
        Queue<File> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            File current = queue.poll();

            if (current.isFile() && current.getName().endsWith(".md")) {
                files.add(current);
                continue;
            }

            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory() && !EXCLUDED_DIRS.contains(child.getName())) {
                            queue.add(child);
                        } else if (child.isFile()) {
                            queue.add(child);
                        }
                    }
                }
            }
        }

        return files;
    }
}
