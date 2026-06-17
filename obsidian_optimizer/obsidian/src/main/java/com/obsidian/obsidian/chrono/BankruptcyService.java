package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.cards.FsrsService;
import com.obsidian.obsidian.cards.FsrsService.FsrsState;
import com.obsidian.obsidian.cards.FsrsStateWriter;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Relief valve: when too many notes pile up overdue, declare bankruptcy and
 * relearn the backlog. Under FSRS this is the mass-lapse — each overdue note
 * goes through {@link FsrsService#forget} (stability collapses, difficulty
 * rises), then is rescheduled by its new FSRS interval, load-balanced across
 * the available days. Legacy notes (sr-due but no FSRS state) are seeded into
 * FSRS first (stability ≈ sr-interval, neutral difficulty) so the whole vault
 * participates. Writes go through {@link FsrsStateWriter} (DB + frontmatter).
 */
@Component
public class BankruptcyService {

    private static final Logger log = LoggerFactory.getLogger(BankruptcyService.class);

    private static final Random RANDOM = new Random();

    private final FsrsService fsrs;
    private final FsrsStateWriter stateWriter;

    public BankruptcyService(FsrsService fsrs, FsrsStateWriter stateWriter) {
        this.fsrs = fsrs;
        this.stateWriter = stateWriter;
    }

    public record BankruptcyResult(int overdueCount, boolean declared, int rescheduled) {}

    public BankruptcyResult run(List<Path> mdFiles, int bankruptcyLimit) {
        LocalDate today = LocalDate.now();
        List<Path> overdue = new ArrayList<>();
        for (Path file : mdFiles) {
            FrontmatterRewriter.SrFields fields = FrontmatterRewriter.read(file);
            if (fields != null && fields.due().isBefore(today)) overdue.add(file);
        }

        log.info("[BankruptcyCheck] {} overdue note(s) found.", overdue.size());
        if (overdue.size() < bankruptcyLimit) {
            log.info("[BankruptcyCheck] No bankruptcy — below threshold of {}.", bankruptcyLimit);
            return new BankruptcyResult(overdue.size(), false, 0);
        }

        log.info("[BankruptcyCheck] BANKRUPTCY DECLARED. Lapsing + spreading {} note(s).", overdue.size());
        Map<LocalDate, Integer> dayLoad = new HashMap<>();
        Instant now = Instant.now();
        int rescheduled = 0;
        for (Path file : overdue) {
            try {
                lapseAndReschedule(file, today, now, dayLoad);
                rescheduled++;
            } catch (Exception e) {
                log.warn("[BankruptcyCheck] Failed to process {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return new BankruptcyResult(overdue.size(), true, rescheduled);
    }

    private void lapseAndReschedule(Path file, LocalDate today, Instant now,
                                    Map<LocalDate, Integer> dayLoad) {
        FrontmatterRewriter.SrFields legacy = FrontmatterRewriter.read(file);
        if (legacy == null) return;  // not a review note
        String notePath = file.toAbsolutePath().toString();

        ReviewRow row = stateWriter.read(notePath);
        FsrsState prior;
        Instant lastReview;
        if (row != null) {
            prior = new FsrsState(row.stability(), row.difficulty());
            lastReview = row.lastReview().toInstant();
        } else {
            // Seed the legacy note into FSRS (same policy as on-demand normalize):
            // stability ≈ sr-interval, difficulty from ease — timeline preserved.
            prior = FsrsStateWriter.seedFromLegacy(legacy);
            lastReview = legacy.due().minusDays(Math.max(1, legacy.interval()))
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        }

        double elapsedDays = Math.max(0, ChronoUnit.SECONDS.between(lastReview, now) / 86400.0);
        FsrsState lapsed = fsrs.forget(prior, elapsedDays);
        int newInterval = fsrs.intervalDays(lapsed.stability());
        LocalDate newDue = leastLoadedDate(today, newInterval, dayLoad);

        stateWriter.writeState(notePath, lapsed, now, newDue, newInterval);
    }

    /** Pick the least-loaded day within [today+1, today+interval]; ties broken randomly. */
    private static LocalDate leastLoadedDate(LocalDate today, int interval, Map<LocalDate, Integer> dayLoad) {
        int minLoad = Integer.MAX_VALUE;
        List<LocalDate> candidates = new ArrayList<>();
        for (int d = 1; d <= Math.max(1, interval); d++) {
            LocalDate candidate = today.plusDays(d);
            int load = dayLoad.getOrDefault(candidate, 0);
            if (load < minLoad) {
                minLoad = load;
                candidates.clear();
                candidates.add(candidate);
            } else if (load == minLoad) {
                candidates.add(candidate);
            }
        }
        LocalDate chosen = candidates.get(RANDOM.nextInt(candidates.size()));
        dayLoad.merge(chosen, 1, Integer::sum);
        return chosen;
    }
}
