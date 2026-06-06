package com.obsidian.obsidian;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileRepository {

    @Value("${VAULT_PATH:C:/Users/ACER/Desktop/NewLife}")
    private String ROOT_FILE;

    ArrayList<String> cache;
    boolean cacheUpToDate = false;

    ArrayList<String> cacheReview;
    boolean cacheReviewUpToDate = false;

    public void invalidateCache() {
        cacheUpToDate = false;
        cacheReviewUpToDate = false;
    }

    public ArrayList<String> getNoteNames() {
        // CACHE DISABLED: re-enable once app is stable
        // if (cache != null && cacheUpToDate) return cache;

        ArrayList<String> names = new ArrayList<>();
        File root = new File(ROOT_FILE);
        Queue<File> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            File current = queue.poll();

            if (current.isFile() && current.getName().endsWith(".md")) {
                names.add(current.getAbsolutePath());
                continue;
            }

            if (current.isDirectory()) {
                File[] files = current.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            String n = file.getName();
                            if (!n.equals(".git") && !n.equals("resources") && !n.equals("_trash")) {
                                queue.add(file);
                            }
                        } else if (file.isFile()) {
                            queue.add(file);
                        }
                    }
                }
            }
        }

        Collections.sort(names);
        cache = names;
        cacheUpToDate = true;
        return cache;
    }

    public ArrayList<String> getReviewNotes() {
        // CACHE DISABLED: re-enable once app is stable
        // if (cacheReview != null && cacheReviewUpToDate) return cacheReview;
        getNoteNames();

        ArrayList<String> reviewNames = new ArrayList<>();
        BufferedReader reader = null;

        for (String pathString : cache) {
            File file = new File(pathString);
            try {
                reader = new BufferedReader(new FileReader(file));
                reader.readLine();
                String line = reader.readLine();
                if (line != null && isBeforeToday(line)) {
                    reviewNames.add(file.toString());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            if (reader != null) reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        cacheReview = reviewNames;
        cacheReviewUpToDate = true;
        return cacheReview;
    }

    public String getText(String path) {
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
            return "";
        }
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

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
        String initialContent = "---\nsr-due: " + srDue + "\nsr-interval: 3\nsr-ease: 200\n---\n\n";
        Files.writeString(noteFile.toPath(), initialContent);

        invalidateCache();
        return noteFile.getAbsolutePath();
    }

    public void updateNote(String path, String content) throws IOException {
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);
        Files.writeString(Paths.get(path), content);
        invalidateCache();
    }

    public void patchNote(String path, List<PatchHunk> hunks) throws IOException {
        if (hunks == null || hunks.isEmpty()) return;
        File file = new File(path);
        if (!file.exists()) throw new IOException("Note not found: " + path);

        String original = Files.readString(Paths.get(path));
        // Detect line separator from original file (preserve CRLF on Windows vaults)
        String sep = original.contains("\r\n") ? "\r\n" : "\n";

        // Normalize to LF for line-index operations
        List<String> lines = new ArrayList<>(
            Arrays.asList(original.replace("\r\n", "\n").split("\n", -1))
        );

        // Apply hunks back-to-front so earlier line numbers stay valid
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

        Files.writeString(Paths.get(path), String.join(sep, lines));
        invalidateCache();
    }

    public String renameNote(String oldPath, String newName) throws IOException {
        File oldFile = new File(oldPath);
        if (!oldFile.exists()) throw new IOException("Note not found: " + oldPath);

        String filename = newName.endsWith(".md") ? newName : newName + ".md";
        File newFile = new File(oldFile.getParent(), filename);

        if (newFile.exists() && !newFile.getAbsolutePath().equals(oldFile.getAbsolutePath())) {
            throw new IOException("A note named '" + newName + "' already exists in this folder");
        }

        if (!oldFile.renameTo(newFile)) {
            throw new IOException("Rename failed");
        }

        invalidateCache();
        return newFile.getAbsolutePath();
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

        invalidateCache();
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
                    if (!n.equals(".git") && !n.equals("resources") && !n.equals("_trash")) {
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

    // Returns up to `limit` review-due notes starting at `offset`.
    // Uses the review cache; warm on first call (O(n)), instant afterwards.
    public ReviewPage getReviewNotesPaged(int offset, int limit) {
        ArrayList<String> all = getReviewNotes();
        int from    = Math.min(offset, all.size());
        int to      = Math.min(from + limit, all.size());
        boolean hasMore = to < all.size();
        return new ReviewPage(new ArrayList<>(all.subList(from, to)), hasMore);
    }

    public record ChildrenResult(String parentPath, List<String> folderPaths, List<String> filePaths) {}
    public record ReviewPage(List<String> notes, boolean hasMore) {}
    public record PatchHunk(int startLine, int deleteCount, List<String> insertLines) {}

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isBeforeToday(String line) {
        if (line.length() < 18) return false;
        String dateString = line.substring(8, 18);
        try {
            LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            return !date.isAfter(LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }
}
