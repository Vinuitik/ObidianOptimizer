package com.obsidian.obsidian.chrono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class SpreadService {

    private static final Logger log = LoggerFactory.getLogger(SpreadService.class);

    public record SpreadResult(int total, int moved) {}

    public SpreadResult run(List<Path> mdFiles, int maxDailyReviews) {
        LocalDate today = LocalDate.now();
        TreeMap<Integer, List<NoteInfo>> byDay = new TreeMap<>();

        for (Path file : mdFiles) {
            FrontmatterRewriter.SrFields fields = FrontmatterRewriter.read(file);
            if (fields == null) continue;
            int delta = (int) ChronoUnit.DAYS.between(today, fields.due());
            byDay.computeIfAbsent(delta, k -> new ArrayList<>())
                 .add(new NoteInfo(file, fields.ease(), fields.due()));
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

            notes.sort(Comparator.comparingInt(n -> n.ease));
            List<NoteInfo> overflow = new ArrayList<>(notes.subList(maxDailyReviews, notes.size()));
            notes.subList(maxDailyReviews, notes.size()).clear();
            byDay.computeIfAbsent(delta + 1, k -> new ArrayList<>()).addAll(overflow);
            maxDay = byDay.lastKey();
        }

        int moved = 0;
        for (Map.Entry<Integer, List<NoteInfo>> entry : byDay.entrySet()) {
            LocalDate assignedDate = today.plusDays(entry.getKey());
            for (NoteInfo note : entry.getValue()) {
                if (!assignedDate.equals(note.originalDue)) {
                    try {
                        FrontmatterRewriter.SrFields current = FrontmatterRewriter.read(note.file);
                        if (current != null) {
                            FrontmatterRewriter.write(note.file,
                                new FrontmatterRewriter.SrFields(assignedDate, current.interval(), current.ease()));
                            moved++;
                        }
                    } catch (IOException e) {
                        log.warn("[SpreadCheck] Failed to update {}: {}", note.file.getFileName(), e.getMessage());
                    }
                }
            }
        }

        log.info("[SpreadCheck] Shifted {} note(s) to enforce max {}/day.", moved, maxDailyReviews);
        return new SpreadResult(total, moved);
    }

    private record NoteInfo(Path file, int ease, LocalDate originalDue) {}
}
