package com.obsidian.obsidian.notes;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository;
import com.obsidian.obsidian.vaults.ActiveVault;

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

    // _reports = agent debug reports (embedder AgentReport); never a note source.
    // _sources = Capture source snapshots (_inbox/_sources/*.md) — pre-rewrite copies,
    // not real notes; never indexed even though nested inside _inbox/.
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", ".obsidian", "_trash", "resources", "_workspace", "_reports", "_sources");

    private final NoteLinkRepository  noteLinkRepo;
    private final SettingsRepository  settingsRepo;
    private final NoteIndexRepository noteIndex;
    private final ImageScanService    imageScanService;
    private final SyncQueueRepository syncQueueRepo;
    private final ActiveVault         activeVault;

    public FileRepository(NoteLinkRepository noteLinkRepo,
                          SettingsRepository settingsRepo,
                          NoteIndexRepository noteIndex,
                          ImageScanService imageScanService,
                          SyncQueueRepository syncQueueRepo,
                          ActiveVault activeVault) {
        this.noteLinkRepo  = noteLinkRepo;
        this.settingsRepo  = settingsRepo;
        this.noteIndex     = noteIndex;
        this.imageScanService = imageScanService;
        this.syncQueueRepo = syncQueueRepo;
        this.activeVault   = activeVault;
    }

    /** Active vault root / id. Everything this class does targets the active vault only. */
    private String root() { return activeVault.root().toString(); }
    private long vid()    { return activeVault.id(); }

    @PostConstruct
    public void init() {
        startupSync();
    }

    /** Switch to another vault: rerun the startup disk sync + image scan for the NEW root
     *  (async — the PUT /vaults/active request must not wait for a full vault walk). */
    @org.springframework.context.event.EventListener
    public void onVaultSwitched(com.obsidian.obsidian.vaults.VaultSwitchedEvent e) {
        log.info("[FileRepository] vault switched {} -> {}: rescanning", e.oldVaultId(), e.newVaultId());
        CompletableFuture.runAsync(this::startupSync);
    }

    private void startupSync() {
        List<File> diskFiles = bfsDiskFiles();

        String syncMode = settingsRepo.getStartupSyncMode(vid());
        if ("async".equals(syncMode)) {
            log.info("[init] async sync started for {}", root());
            CompletableFuture.runAsync(() -> {
                noteIndex.syncWithDisk(vid(), diskFiles);
                imageScanService.scanAll(vid(), diskFiles);
            });
        } else {
            log.info("[init] blocking sync started for {}", root());
            noteIndex.syncWithDisk(vid(), diskFiles);
            log.info("[init] blocking sync complete");
            imageScanService.scanAll(vid(), diskFiles);
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public ArrayList<String> getNoteNames() {
        return noteIndex.getAllPaths(vid());
    }

    public String getText(String path) {
        try {
            requireInsideVault(path);
            String content = Files.readString(Paths.get(path));
            log.debug("[getText] read {} bytes from {}", content.length(), path);
            return content;
        } catch (IOException e) {
            log.error("[getText] failed to read path={}: {}", path, e.getMessage());
            return "";
        }
    }

    public String getRootPath() {
        return new File(root()).getAbsolutePath();
    }

    public List<Path> listMdPaths() {
        return bfsDiskFiles().stream()
                .map(File::toPath)
                .collect(java.util.stream.Collectors.toList());
    }

    public void triggerDeltaSync() {
        log.info("[FileRepository] Post-chrono delta sync.");
        noteIndex.syncWithDisk(vid(), bfsDiskFiles());
    }

    public ChildrenResult getDirectChildren(String folderPath) {
        List<String> folderPaths = new ArrayList<>();
        List<String> filePaths   = new ArrayList<>();
        try {
            requireInsideVault(folderPath);
        } catch (IOException e) {
            log.warn("[getDirectChildren] rejected: {}", e.getMessage());
            return new ChildrenResult(folderPath, folderPaths, filePaths);
        }
        File dir = new File(folderPath);

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

    public ReviewPage getReviewNotesPaged(int offset, int limit) {
        return noteIndex.getReviewNotesPaged(vid(), offset, limit);
    }

    /** Same due query, but each note tagged with whether it has active flashcards —
     *  the hybrid-review UI uses this to split the daily set into flashcard vs read tracks. */
    public ReviewPageInfo getReviewNotesPagedWithCards(int offset, int limit) {
        return noteIndex.getReviewNotesPagedWithCards(vid(), offset, limit);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public String createFolder(String parentPath, String name) throws IOException {
        requireInsideVault(parentPath);
        requireSimpleName(name);
        File parent = new File(parentPath);
        if (!parent.exists() || !parent.isDirectory()) {
            throw new IOException("Parent folder not found: " + parentPath);
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
        return createNote(vid(), folderPath, name);
    }

    /** Vault-explicit variant for callbacks from in-flight jobs (embedder ingest): writes into
     *  {@code vaultId}'s root, NOT whichever vault is active now. */
    public String createNote(long vaultId, String folderPath, String name) throws IOException {
        String root = rootOf(vaultId);
        requireInsideVault(root, folderPath);
        requireSimpleName(name);
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
        String initialContent = "---\nsr-due: " + srDue + "\nsr-interval: 3\n---\n\n#review\n";
        Files.writeString(noteFile.toPath(), initialContent);

        String path = noteFile.getAbsolutePath();
        FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(initialContent);
        noteIndex.upsert(vaultId, path, name.replace(".md", ""), meta, noteFile.lastModified());
        noteLinkRepo.updateLinks(vaultId, path, NoteLinkRepository.extractTargets(initialContent));
        imageScanService.registerImages(vaultId, path, initialContent);
        syncQueueRepo.markPending(vaultId, toRelative(root, path), ContentHashing.sha256(initialContent));
        return path;
    }

    public void updateNote(String path, String content) throws IOException {
        updateNote(vid(), path, content);
    }

    /** Vault-explicit variant (see {@link #createNote(long, String, String)}). */
    public void updateNote(long vaultId, String path, String content) throws IOException {
        String root = rootOf(vaultId);
        requireInsideVault(root, path);
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);
        Files.writeString(Paths.get(path), content);

        String title = file.getName().replace(".md", "");
        FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(content);
        noteIndex.upsert(vaultId, path, title, meta, file.lastModified());
        noteLinkRepo.updateLinks(vaultId, path, NoteLinkRepository.extractTargets(content));
        imageScanService.registerImages(vaultId, path, content);
        syncQueueRepo.markPending(vaultId, toRelative(root, path), ContentHashing.sha256(content));
    }

    public void patchNote(String path, List<PatchHunk> hunks) throws IOException {
        log.info("[patchNote] applying {} hunks to {}", hunks == null ? 0 : hunks.size(), path);
        if (hunks == null || hunks.isEmpty()) return;
        requireInsideVault(path);
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
        noteIndex.upsert(vid(), path, title, meta, file.lastModified());
        noteLinkRepo.updateLinks(vid(), path, NoteLinkRepository.extractTargets(newContent));
        imageScanService.registerImages(vid(), path, newContent);
        syncQueueRepo.markPending(vid(), toRelative(path), ContentHashing.sha256(newContent));
    }

    public String renameNote(String oldPath, String newName) throws IOException {
        requireInsideVault(oldPath);
        requireSimpleName(newName);
        File oldFile = new File(oldPath);
        if (!oldFile.exists()) throw new IOException("Note not found: " + oldPath);

        String oldName = oldFile.getName().replace(".md", "");
        String filename = newName.endsWith(".md") ? newName : newName + ".md";
        File newFile = new File(oldFile.getParent(), filename);

        if (newFile.exists() && !newFile.getAbsolutePath().equals(oldFile.getAbsolutePath())) {
            throw new IOException("A note named '" + newName + "' already exists in this folder");
        }

        List<String> sources = noteLinkRepo.findSourcesByTarget(vid(), oldName);

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
                    // Rewritten backlink sources changed on disk — keep their index
                    // hash current and queue them for Drive sync, or the Drive copy
                    // stays stale until the next unrelated edit.
                    imageScanService.registerImages(vid(), sourcePath, updated);
                    syncQueueRepo.markPending(vid(), toRelative(sourcePath), ContentHashing.sha256(updated));
                }
            } catch (IOException e) {
                log.warn("[renameNote] failed to rewrite links in {}: {}", sourcePath, e.getMessage());
            }
        }

        noteIndex.rename(vid(), oldPath, newPath, newName);
        noteLinkRepo.renameTarget(vid(), oldName, newName);
        noteLinkRepo.renameSource(vid(), oldPath, newPath);
        syncQueueRepo.tombstone(vid(), toRelative(oldPath));
        try {
            String content = Files.readString(Paths.get(newPath));
            syncQueueRepo.markPending(vid(), toRelative(newPath), ContentHashing.sha256(content));
        } catch (IOException e) {
            log.warn("[renameNote] sync queue update failed for {}: {}", newPath, e.getMessage());
        }
        return newPath;
    }

    public String moveNote(String sourcePath, String targetFolder) throws IOException {
        requireInsideVault(sourcePath);
        requireInsideVault(targetFolder);
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) throw new IOException("Note not found: " + sourcePath);

        File targetDir = new File(targetFolder);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            throw new IOException("Target folder not found: " + targetFolder);
        }

        File destFile = new File(targetDir, sourceFile.getName());
        if (destFile.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
            return sourcePath;
        }
        if (destFile.exists()) {
            throw new IOException("A note named '" + sourceFile.getName() + "' already exists in the target folder");
        }

        Path newPath = Files.move(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        String newAbsPath = newPath.toFile().getAbsolutePath();
        String title = sourceFile.getName().replace(".md", "");

        noteIndex.rename(vid(), sourcePath, newAbsPath, title);
        noteLinkRepo.renameSource(vid(), sourcePath, newAbsPath);
        syncQueueRepo.tombstone(vid(), toRelative(sourcePath));
        try {
            String content = Files.readString(newPath);
            syncQueueRepo.markPending(vid(), toRelative(newAbsPath), ContentHashing.sha256(content));
        } catch (IOException e) {
            log.warn("[moveNote] sync queue update failed for {}: {}", newAbsPath, e.getMessage());
        }
        return newAbsPath;
    }

    public void softDeleteNote(String path) throws IOException {
        requireInsideVault(path);
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);

        File trashDir = new File(root(), "_trash");
        if (!trashDir.exists()) trashDir.mkdirs();

        File dest = new File(trashDir, file.getName());
        if (dest.exists()) {
            String base = file.getName().replace(".md", "");
            dest = new File(trashDir, base + "_" + System.currentTimeMillis() + ".md");
        }

        if (!file.renameTo(dest)) throw new IOException("Failed to move note to trash");

        noteIndex.delete(vid(), path);
        noteLinkRepo.deleteSource(vid(), path);
        syncQueueRepo.tombstone(vid(), toRelative(path));
    }

    /**
     * Soft-delete an arbitrary vault file (not a note tracked by the note index —
     * e.g. a Capture source snapshot under _inbox/_sources/) into _trash/. No
     * noteIndex/noteLinkRepo bookkeeping, since these were never indexed as notes.
     */
    public void softDeleteFile(String path) throws IOException {
        requireInsideVault(path);
        File file = new File(path);
        if (!file.exists()) return; // already gone — acknowledge is idempotent

        File trashDir = new File(root(), "_trash");
        if (!trashDir.exists()) trashDir.mkdirs();

        File dest = new File(trashDir, file.getName());
        if (dest.exists()) {
            String base = file.getName();
            int dot = base.lastIndexOf('.');
            dest = new File(trashDir, (dot >= 0 ? base.substring(0, dot) : base)
                + "_" + System.currentTimeMillis() + (dot >= 0 ? base.substring(dot) : ""));
        }
        if (!file.renameTo(dest)) throw new IOException("Failed to move file to trash: " + path);
    }

    /**
     * Soft-delete a whole folder (and everything under it) into _trash/. Mirrors
     * softDeleteNote's bookkeeping for every indexed note beneath the folder:
     * purge from the note index + link graph, and tombstone in the Drive-sync
     * queue so the deletion propagates. The folder is moved (renamed) as a unit,
     * so nested subfolders and non-note files ride along untouched.
     *
     * Guards: must be inside the vault, must exist, and cannot be the vault root
     * or the _trash folder itself.
     */
    public void softDeleteFolder(String path) throws IOException {
        Path candidate = requireInsideVault(path);
        File folder = new File(path);
        if (!folder.exists() || !folder.isDirectory()) {
            throw new IOException("Folder not found: " + path);
        }

        Path vaultRoot = Paths.get(root()).toAbsolutePath().normalize();
        if (candidate.equals(vaultRoot)) {
            throw new IOException("Cannot delete the vault root");
        }
        File trashDir = new File(root(), "_trash");
        if (candidate.equals(trashDir.getAbsoluteFile().toPath().normalize())) {
            throw new IOException("Cannot delete the trash folder");
        }
        if (!trashDir.exists()) trashDir.mkdirs();

        // 1. Purge index/links/sync for every indexed note under this folder,
        //    BEFORE the move (paths are absolute, so a prefix match is exact).
        String prefix = folder.getAbsolutePath() + File.separator;
        for (String notePath : noteIndex.getAllPaths(vid())) {
            if (notePath.startsWith(prefix)) {
                noteIndex.delete(vid(), notePath);
                noteLinkRepo.deleteSource(vid(), notePath);
                syncQueueRepo.tombstone(vid(), toRelative(notePath));
            }
        }

        // 2. Move the folder into _trash as a unit (collision-suffixed).
        File dest = new File(trashDir, folder.getName());
        if (dest.exists()) {
            dest = new File(trashDir, folder.getName() + "_" + System.currentTimeMillis());
        }
        if (!folder.renameTo(dest)) {
            throw new IOException("Failed to move folder to trash: " + path);
        }
    }

    private String toRelative(String absPath) { return toRelative(root(), absPath); }

    private static String toRelative(String root, String absPath) {
        return Paths.get(root).relativize(Paths.get(absPath)).toString().replace('\\', '/');
    }

    private String rootOf(long vaultId) { return activeVault.rootFor(vaultId).toString(); }

    /**
     * Re-index one note after something other than this class wrote it to disk
     * (e.g. ReviewService updating sr-due frontmatter after an FSRS grade).
     * Same bookkeeping as updateNote minus the write itself.
     */
    public void reindexAfterExternalWrite(String path) throws IOException {
        requireInsideVault(path);
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);
        String content = Files.readString(Paths.get(path));
        noteIndex.upsert(vid(), path, file.getName().replace(".md", ""),
            FrontmatterParser.parse(content), file.lastModified());
        noteLinkRepo.updateLinks(vid(), path, NoteLinkRepository.extractTargets(content));
        imageScanService.registerImages(vid(), path, content);
        syncQueueRepo.markPending(vid(), toRelative(path), ContentHashing.sha256(content));
    }

    // ── Path guards ───────────────────────────────────────────────────────────

    /**
     * Every client-supplied path must resolve inside the vault. Without this,
     * any authenticated request could read or write arbitrary files.
     */
    Path requireInsideVault(String rawPath) throws IOException {
        return requireInsideVault(root(), rawPath);
    }

    Path requireInsideVault(String root, String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException("Path required");
        }
        Path vaultRoot = Paths.get(root).toAbsolutePath().normalize();
        Path candidate = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(vaultRoot)) {
            throw new IOException("Path is outside the vault: " + rawPath);
        }
        return candidate;
    }

    /** File/folder names must be a single path segment — no separators, no traversal. */
    static void requireSimpleName(String name) throws IOException {
        if (name == null || name.isBlank()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("Invalid name: " + name);
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record ChildrenResult(String parentPath, List<String> folderPaths, List<String> filePaths) {}
    public record ReviewPage(List<String> notes, boolean hasMore) {}
    public record ReviewNoteInfo(String path, boolean hasCards) {}
    public record ReviewPageInfo(List<ReviewNoteInfo> notes, boolean hasMore) {}
    public record PatchHunk(int startLine, int deleteCount, List<String> insertLines) {}

    // ── Private ───────────────────────────────────────────────────────────────

    private List<File> bfsDiskFiles() {
        List<File> files = new ArrayList<>();
        File root = new File(root());
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
