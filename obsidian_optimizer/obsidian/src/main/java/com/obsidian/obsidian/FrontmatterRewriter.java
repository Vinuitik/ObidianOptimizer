package com.obsidian.obsidian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class FrontmatterRewriter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record SrFields(LocalDate due, int interval, int ease) {}

    // Reads sr-due/sr-interval/sr-ease from any .md file. Returns null if no valid sr-due found.
    public static SrFields read(Path file) {
        try {
            String raw = Files.readString(file);
            String content = raw.replace("\r\n", "\n");
            LocalDate due = null;
            int interval = 3;
            int ease = 200;
            for (String line : content.split("\n")) {
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

    // Rewrites sr-due/sr-interval/sr-ease lines in place, preserving all other content and line endings.
    public static void write(Path file, SrFields fields) throws IOException {
        String raw = Files.readString(file);
        String sep = raw.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
        String newDue = fields.due().format(FMT);
        for (int i = 0; i < lines.length; i++) {
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

    // Returns true if line 2 of the file ends with "Invalid date" — Obsidian SR corruption marker.
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
