package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.cards.FsrsService;
import com.obsidian.obsidian.cards.FsrsStateWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Caps reviews per day by cascading overflow forward. Pure calendar work — it
 * never touches FSRS memory state (stability/difficulty), only the due date,
 * the same Option-A separation the bandit uses. On an overloaded day the
 * HARDEST notes (highest FSRS difficulty) stay put; the easiest spill to the
 * next day. FSRS notes move via {@link FsrsStateWriter#reschedule} (DB +
 * frontmatter); legacy notes just have their sr-due rewritten in place.
 */
@Component
public class SpreadService {

    private static final Logger log = LoggerFactory.getLogger(SpreadService.class);

    private final FsrsStateWriter stateWriter;

    public SpreadService(FsrsStateWriter stateWriter) {
        this.stateWriter = stateWriter;
    }

    public record SpreadResult(int total, int moved) {}

    public SpreadResult run(List<Path> mdFiles, int maxDailyReviews) {
        LocalDate today = LocalDate.now();
        TreeMap<Integer, List<NoteInfo>> byDay = new TreeMap<>();

        for (Path file : mdFiles) {
            FrontmatterRewriter.SrFields legacy = FrontmatterRewriter.read(file);
            if (legacy == null) continue;
            FrontmatterRewriter.FsrsFields fsrs = FrontmatterRewriter.readFsrs(file);
            double hardness = fsrs != null ? fsrs.difficulty() : FsrsService.easeToDifficulty(legacy.ease());
            int delta = (int) ChronoUnit.DAYS.between(today, legacy.due());
            byDay.computeIfAbsent(delta, k -> new ArrayList<>())
                 .add(new NoteInfo(file, hardness, legacy.due(), fsrs != null, legacy));
        }

        if (byDay.isEmpty()) {
            log.info("[SpreadCheck] No notes found.");
            return new SpreadResult(0, 0);
        }

        int total = byDay.values().stream().mapToInt(List::size).sum();
        log.info("[SpreadCheck] {} note(s) across {} day(s).", total, byDay.size());

        int maxDay = byDay.lastKey();
        for (int delta = byDay.firstKey(); delta <= maxDay; delta++) {
            List<NoteInfo> notes = byDay.get(delta);
            if (notes == null || notes.size() <= maxDailyReviews) continue;
            // Hardest first → the top maxDailyReviews stay; the easiest overflow.
            notes.sort(Comparator.comparingDouble((NoteInfo n) -> n.hardness).reversed());
            List<NoteInfo> overflow = new ArrayList<>(notes.subList(maxDailyReviews, notes.size()));
            notes.subList(maxDailyReviews, notes.size()).clear();
            byDay.computeIfAbsent(delta + 1, k -> new ArrayList<>()).addAll(overflow);
            maxDay = byDay.lastKey();
        }

        int moved = 0;
        for (Map.Entry<Integer, List<NoteInfo>> entry : byDay.entrySet()) {
            LocalDate assignedDate = today.plusDays(entry.getKey());
            for (NoteInfo note : entry.getValue()) {
                if (assignedDate.equals(note.originalDue)) continue;
                if (moveDate(note, assignedDate)) moved++;
            }
        }

        log.info("[SpreadCheck] Shifted {} note(s) to enforce max {}/day.", moved, maxDailyReviews);
        return new SpreadResult(total, moved);
    }

    private boolean moveDate(NoteInfo note, LocalDate newDue) {
        try {
            if (note.isFsrs) {
                stateWriter.reschedule(note.file.toAbsolutePath().toString(), newDue);
            } else {
                FrontmatterRewriter.write(note.file, new FrontmatterRewriter.SrFields(
                    newDue, note.legacy.interval(), note.legacy.ease()));
            }
            return true;
        } catch (IOException e) {
            log.warn("[SpreadCheck] Failed to update {}: {}", note.file.getFileName(), e.getMessage());
            return false;
        }
    }

    private record NoteInfo(Path file, double hardness, LocalDate originalDue,
                            boolean isFsrs, FrontmatterRewriter.SrFields legacy) {}
}
