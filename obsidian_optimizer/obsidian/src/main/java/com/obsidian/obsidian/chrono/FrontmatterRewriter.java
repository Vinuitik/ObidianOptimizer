package com.obsidian.obsidian.chrono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class FrontmatterRewriter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record SrFields(LocalDate due, int interval, int ease) {}

    /**
     * Line range [start, end) of the frontmatter body — the lines strictly
     * between the opening and closing "---". Returns null when there is no
     * frontmatter block. Restricting reads/writes to this range means an
     * "sr-due:" mention in a note body or code fence is never touched
     * (matches FrontmatterParser semantics in the notes package).
     */
    private static int[] frontmatterBounds(String[] lines) {
        if (lines.length == 0 || !lines[0].trim().equals("---")) return null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) return new int[]{1, i};
        }
        return null;
    }

    public static SrFields read(Path file) {
        try {
            String raw = Files.readString(file);
            String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
            int[] bounds = frontmatterBounds(lines);
            if (bounds == null) return null;
            LocalDate due = null;
            int interval = 3;
            int ease = 200;
            for (int i = bounds[0]; i < bounds[1]; i++) {
                String line = lines[i];
                if (line.startsWith("sr-due:")) {
                    String val = line.substring("sr-due:".length()).trim();
                    try { due = LocalDate.parse(val, FMT); } catch (Exception ignored) {}
                } else if (line.startsWith("sr-interval:")) {
                    try { interval = Integer.parseInt(line.substring("sr-interval:".length()).trim()); } catch (Exception ignored) {}
                } else if (line.startsWith("sr-ease:")) {
                    try { ease = Integer.parseInt(line.substring("sr-ease:".length()).trim()); } catch (Exception ignored) {}
                }
            }
            return due != null ? new SrFields(due, interval, ease) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static void write(Path file, SrFields fields) throws IOException {
        String raw = Files.readString(file);
        String sep = raw.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
        int[] bounds = frontmatterBounds(lines);
        if (bounds == null) return;  // no frontmatter — nothing to rewrite
        String newDue = fields.due().format(FMT);
        for (int i = bounds[0]; i < bounds[1]; i++) {
            String line = lines[i];
            if (line.startsWith("sr-due:")) {
                lines[i] = "sr-due: " + newDue;
            } else if (line.startsWith("sr-interval:")) {
                lines[i] = "sr-interval: " + fields.interval();
            } else if (line.startsWith("sr-ease:")) {
                lines[i] = "sr-ease: " + fields.ease();
            }
        }
        Files.writeString(file, String.join(sep, lines));
    }

    public static boolean hasInvalidDate(Path file) {
        try {
            String raw = Files.readString(file);
            String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
            return lines.length >= 2 && lines[1].endsWith("Invalid date");
        } catch (IOException e) {
            return false;
        }
    }
}
