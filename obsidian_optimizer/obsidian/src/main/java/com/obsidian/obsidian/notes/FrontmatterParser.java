package com.obsidian.obsidian.notes;

import java.time.LocalDate;

public class FrontmatterParser {

    // captureId / captureSeq link a proposed note back to the Capture that produced it
    // (see CAPTURE_ARCH.md). Frontmatter is the durable source of truth — mirrored into
    // notes.capture_id / capture_seq by NoteIndexRepository so the link survives a
    // forceResync (which rebuilds the index from disk).
    public record NoteMetadata(LocalDate srDue, Integer srInterval, Integer srEase,
                               String captureId, Integer captureSeq) {}

    public static NoteMetadata parse(String rawContent) {
        if (rawContent == null) return empty();

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
        String   captureId   = null;
        Integer  captureSeq  = null;

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
                case "capture-id" -> {
                    if (!val.isEmpty()) captureId = val;
                }
                case "capture-seq" -> {
                    try { captureSeq = Integer.parseInt(val); } catch (Exception ignored) {}
                }
            }
        }

        return new NoteMetadata(srDue, srInterval, srEase, captureId, captureSeq);
    }

    private static NoteMetadata empty() {
        return new NoteMetadata(null, null, null, null, null);
    }
}
