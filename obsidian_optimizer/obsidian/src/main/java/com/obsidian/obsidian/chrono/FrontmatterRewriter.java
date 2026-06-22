package com.obsidian.obsidian.chrono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FrontmatterRewriter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record SrFields(LocalDate due, int interval, int ease) {}

    /**
     * Full FSRS mirror carried in the note frontmatter (the offline/volume-reset
     * copy of {@code note_reviews}). {@code sr-due}/{@code sr-interval} stay for
     * the legacy queue, chrono and Obsidian-SR; {@code sr-ease} is preserved
     * untouched. {@code arm}/{@code bucket} are the pending bandit decision so a
     * review graded anywhere can credit the right Beta cell. Nullable arm/bucket
     * mean "no scheduling decision recorded yet".
     */
    public record FsrsFields(LocalDate due, int interval, double stability, double difficulty,
                             LocalDate lastReview, Double arm, String bucket) {}

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

    // ── FSRS mirror ─────────────────────────────────────────────────────────

    /**
     * Reads the FSRS state mirror. Returns null when the note has no frontmatter
     * block OR carries no FSRS state yet (no {@code fsrs-s}) — a legacy note that
     * has only ever seen the old sr-fields. Callers treat null as "no state,
     * start fresh on next grade".
     */
    public static FsrsFields readFsrs(Path file) {
        try {
            String[] lines = Files.readString(file).replace("\r\n", "\n").split("\n", -1);
            int[] bounds = frontmatterBounds(lines);
            if (bounds == null) return null;
            LocalDate due = null, lastReview = null;
            int interval = 1;
            Double stability = null, difficulty = null, arm = null;
            String bucket = null;
            for (int i = bounds[0]; i < bounds[1]; i++) {
                String line = lines[i];
                if      (line.startsWith("sr-due:"))      due        = parseDate(value(line, "sr-due:"));
                else if (line.startsWith("sr-interval:")) interval   = parseInt(value(line, "sr-interval:"), 1);
                else if (line.startsWith("fsrs-s:"))      stability  = parseDouble(value(line, "fsrs-s:"));
                else if (line.startsWith("fsrs-d:"))      difficulty = parseDouble(value(line, "fsrs-d:"));
                else if (line.startsWith("fsrs-last:"))   lastReview = parseDate(value(line, "fsrs-last:"));
                else if (line.startsWith("fsrs-arm:"))    arm        = parseDouble(value(line, "fsrs-arm:"));
                else if (line.startsWith("fsrs-bucket:")) bucket     = value(line, "fsrs-bucket:");
            }
            if (stability == null || difficulty == null || due == null) return null;
            return new FsrsFields(due, interval, stability, difficulty, lastReview, arm, bucket);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Upserts the FSRS mirror in place: existing keys are overwritten, missing
     * keys appended just before the closing {@code ---}. Everything else in the
     * frontmatter (including {@code sr-ease}) is left untouched. Files without a
     * frontmatter block are skipped.
     */
    public static boolean writeFsrs(Path file, FsrsFields f) throws IOException {
        String raw = Files.readString(file);
        String sep = raw.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(raw.replace("\r\n", "\n").split("\n", -1)));
        int[] bounds = frontmatterBounds(lines.toArray(new String[0]));
        if (bounds == null) return false;

        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        kv.put("sr-due", f.due().format(FMT));
        kv.put("sr-interval", String.valueOf(f.interval()));
        kv.put("fsrs-s", fmtNum(f.stability()));
        kv.put("fsrs-d", fmtNum(f.difficulty()));
        if (f.lastReview() != null) kv.put("fsrs-last", f.lastReview().format(FMT));
        if (f.arm() != null)        kv.put("fsrs-arm", fmtNum(f.arm()));
        if (f.bucket() != null)     kv.put("fsrs-bucket", f.bucket());

        int end = bounds[1];  // index of closing "---"
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String prefix = e.getKey() + ":";
            String rendered = e.getKey() + ": " + e.getValue();
            boolean found = false;
            for (int i = bounds[0]; i < end; i++) {
                if (lines.get(i).startsWith(prefix)) { lines.set(i, rendered); found = true; break; }
            }
            if (!found) { lines.add(end, rendered); end++; }
        }
        Files.writeString(file, String.join(sep, lines));
        return true;
    }

    private static String value(String line, String prefix) { return line.substring(prefix.length()).trim(); }
    private static String fmtNum(double v) { return String.format(java.util.Locale.ROOT, "%.6f", v); }
    private static LocalDate parseDate(String v) { try { return LocalDate.parse(v, FMT); } catch (Exception e) { return null; } }
    private static Double parseDouble(String v) { try { return Double.parseDouble(v); } catch (Exception e) { return null; } }
    private static int parseInt(String v, int def) { try { return Integer.parseInt(v); } catch (Exception e) { return def; } }

}
