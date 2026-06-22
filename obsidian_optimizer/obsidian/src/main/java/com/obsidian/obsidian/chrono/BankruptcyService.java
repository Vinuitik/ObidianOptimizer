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
 * Two-pass nightly lapse job:
 *
 * Pass 1 — Chronic neglect (always runs, per-note, no threshold):
 *   Notes overdue by more than {@code chronicNeglectDays} are individually lapsed
 *   via {@link FsrsService#forget}. Prevents notes from drifting forgotten for
 *   months while the total overdue count stays below the bankruptcy limit.
 *   Configurable via Settings → "chronicNeglectDays" (default 7).
 *
 * Pass 2 — Mass bankruptcy (threshold gate):
 *   If the total overdue count (chronic + standard) meets or exceeds
 *   {@code bankruptcyLimit}, ALL remaining overdue notes (those not already
 *   lapsed in pass 1) get the same forget+reschedule treatment in one sweep.
 *
 * Both passes write through {@link FsrsStateWriter} (DB + frontmatter mirror).
 * Legacy notes are seeded into FSRS first (S ≈ sr-interval, D from ease).
 *
 * Deliberately NO bandit reward here. A bankruptcy/neglect lapse is exogenous —
 * the user didn't open the app — not evidence that the interval was wrong, so
 * feeding it to the bandit would punish good long arms for the user's absence.
 * The bandit only learns from genuine reviews (see BanditService, ReviewService).
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

    public record BankruptcyResult(
        int overdueCount,
        int chronicNeglected,
        boolean declared,
        int rescheduled
    ) {}

    public BankruptcyResult run(List<Path> mdFiles, int bankruptcyLimit, int chronicNeglectDays) {
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        List<Path> chronic  = new ArrayList<>();  // overdue > chronicNeglectDays
        List<Path> standard = new ArrayList<>();  // overdue but ≤ chronicNeglectDays

        for (Path file : mdFiles) {
            FrontmatterRewriter.SrFields fields = FrontmatterRewriter.read(file);
            if (fields == null || !fields.due().isBefore(today)) continue;
            long daysOverdue = ChronoUnit.DAYS.between(fields.due(), today);
            if (daysOverdue > chronicNeglectDays) {
                chronic.add(file);
            } else {
                standard.add(file);
            }
        }

        int totalOverdue = chronic.size() + standard.size();
        log.info("[BankruptcyCheck] {} overdue note(s): {} chronic (>{} days), {} standard.",
            totalOverdue, chronic.size(), chronicNeglectDays, standard.size());

        // Pass 1: always lapse chronically neglected notes individually.
        Map<LocalDate, Integer> dayLoad = new HashMap<>();
        int chronicNeglected = 0;
        for (Path file : chronic) {
            try {
                lapseAndReschedule(file, today, now, dayLoad);
                chronicNeglected++;
            } catch (Exception e) {
                log.warn("[BankruptcyCheck] Failed chronic lapse of {}: {}", file.getFileName(), e.getMessage());
            }
        }
        if (chronicNeglected > 0) {
            log.info("[BankruptcyCheck] Lapsed {} chronically neglected note(s).", chronicNeglected);
        }

        // Pass 2: mass bankruptcy if total overdue meets the threshold.
        if (totalOverdue < bankruptcyLimit) {
            log.info("[BankruptcyCheck] No bankruptcy — {} overdue below threshold of {}.",
                totalOverdue, bankruptcyLimit);
            return new BankruptcyResult(totalOverdue, chronicNeglected, false, 0);
        }

        log.info("[BankruptcyCheck] BANKRUPTCY DECLARED. Lapsing {} remaining standard note(s).", standard.size());
        int rescheduled = 0;
        for (Path file : standard) {
            try {
                lapseAndReschedule(file, today, now, dayLoad);
                rescheduled++;
            } catch (Exception e) {
                log.warn("[BankruptcyCheck] Failed mass lapse of {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return new BankruptcyResult(totalOverdue, chronicNeglected, true, rescheduled);
    }

    private void lapseAndReschedule(Path file, LocalDate today, Instant now,
                                    Map<LocalDate, Integer> dayLoad) {
        FrontmatterRewriter.SrFields legacy = FrontmatterRewriter.read(file);
        if (legacy == null) return;
        String notePath = file.toAbsolutePath().toString();

        ReviewRow row = stateWriter.read(notePath);
        FsrsState prior;
        Instant lastReview;
        if (row != null) {
            prior = new FsrsState(row.stability(), row.difficulty());
            lastReview = row.lastReview().toInstant();
        } else {
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
