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
import java.util.Collections;
import java.util.Queue;
import org.springframework.stereotype.Component;

@Component
public class FileRepository {

    private final String ROOT_FILE = "C:\\Users\\ACER\\Desktop\\NewLife";

    ArrayList<String> cache;
    boolean cacheUpToDate = false;

    ArrayList<String> cacheReview;
    boolean cacheReviewUpToDate = false;

    public void invalidateCache() {
        cacheUpToDate = false;
        cacheReviewUpToDate = false;
    }

    public ArrayList<String> getNoteNames() {
        if (cache != null && cacheUpToDate) return cache;

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
        if (cacheReview != null && cacheReviewUpToDate) return cacheReview;
        if (cache == null || !cacheUpToDate) getNoteNames();

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

        String initialContent = "---\nreviewed: " + LocalDate.now() + "\n---\n\n";
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
