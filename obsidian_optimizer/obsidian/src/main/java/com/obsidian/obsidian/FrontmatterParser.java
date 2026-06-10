package com.obsidian.obsidian;

import java.time.LocalDate;

public class FrontmatterParser {

    public record NoteMetadata(LocalDate srDue, Integer srInterval, Integer srEase) {}

    public static NoteMetadata parse(String rawContent) {
        if (rawContent == null) return empty();

        // Normalise line endings so indexOf works regardless of file origin
        String content = rawContent.replace("\r\n", "\n");

        if (!content.startsWith("---")) return empty();

        int blockStart = content.indexOf('\n');
        if (blockStart < 0) return empty();

        int blockEnd = content.indexOf("\n---", blockStart);
        if (blockEnd < 0) return empty();

        String block = content.substring(blockStart + 1, blockEnd);

        LocalDate srDue      = null;
        Integer  srInterval  = null;
        Integer  srEase      = null;

        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();

            switch (key) {
                case "sr-due" -> {
                    try { srDue = LocalDate.parse(val); } catch (Exception ignored) {}
                }
                case "sr-interval" -> {
                    try { srInterval = Integer.parseInt(val); } catch (Exception ignored) {}
                }
                case "sr-ease" -> {
                    try { srEase = Integer.parseInt(val); } catch (Exception ignored) {}
                }
            }
        }

        return new NoteMetadata(srDue, srInterval, srEase);
    }

    private static NoteMetadata empty() {
        return new NoteMetadata(null, null, null);
    }
}
