package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure read-time computation of "what's on Today" — no persisted daily-plan table, no
 * {@code @Scheduled} job. An unfinished item just stays {@code pending} and resurfaces
 * tomorrow; carryover is emergent, not stored (same principle as
 * {@code pwa/reviewPlan.js}'s {@code allocateTracks()}).
 *
 * Phase 1c: deadline tracks get a capacity-weighted daily pace, recomputed fresh on every
 * read (never stored); manual (non-deadline) tracks keep Phase 1's flat
 * {@code track_schedule} allocation, now capped by whatever capacity the deadline tracks
 * didn't reserve. See tracks/FLOWS.md "Today Flow" for the full algorithm writeup and the
 * two design decisions (Lock-in = single-track focus, Normal-mode must-overflow still
 * capacity-bounded) resolved before this was implemented.
 */
@Service
public class TodayPlanService {

    public static final String MODE_KEY = "trackMode";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_LOCKIN = "lockin";

    private final TrackRepository trackRepo;
    private final SettingsRepository settingsRepo;

    public TodayPlanService(TrackRepository trackRepo, SettingsRepository settingsRepo) {
        this.trackRepo = trackRepo;
        this.settingsRepo = settingsRepo;
    }

    public record TodayItem(long itemId, long trackId, String trackTitle, String trackType,
                            String title, String notePath) {}

    /** {@code overBudget}: Normal mode's must-priority deadline tracks alone exceeded
     *  today's capacity and got pro-rata trimmed anyway (not a silent drop) — the signal
     *  the frontend uses to show a "you're behind — switch to Lock-in?" banner. Never set
     *  in Lock-in mode (there's no capacity ceiling to overflow). */
    public record TodayPlan(List<TodayItem> items, String mode, boolean overBudget) {}

    public String getMode() {
        return settingsRepo.getOrDefault(MODE_KEY, MODE_NORMAL);
    }

    public void setMode(String mode) {
        settingsRepo.set(MODE_KEY, mode);
    }

    public TodayPlan today() {
        return today(LocalDate.now());
    }

    /** Date-parameterized for testability — 0=Mon..6=Sun, matching track_schedule.weekday
     *  and daily_capacity.weekday. */
    TodayPlan today(LocalDate date) {
        String mode = getMode();
        int weekday = weekdayOf(date);
        double todayCap = trackRepo.getCapacity(weekday);

        List<Track> active = trackRepo.listActive();
        List<Track> deadlineTracks = new ArrayList<>();
        List<Track> manualTracks = new ArrayList<>();
        for (Track t : active) {
            if (t.deadline() != null) deadlineTracks.add(t); else manualTracks.add(t);
        }

        // itemsRemaining / pace only meaningful for tracks that still have pending work.
        Map<Long, Integer> remaining = new HashMap<>();
        Map<Long, Double> pace = new HashMap<>();
        deadlineTracks.removeIf(t -> {
            int n = trackRepo.countPendingItems(t.id());
            if (n == 0) return true;
            remaining.put(t.id(), n);
            double capSpan = capacityBetween(date, t.deadline());
            // Deadline today/past (capSpan<=0): nothing left to spread it over, so the
            // track wants everything NOW — treat pace as "all remaining items, today".
            pace.put(t.id(), capSpan > 0 ? n / capSpan : n);
            return false;
        });

        if (MODE_LOCKIN.equals(mode) && !deadlineTracks.isEmpty()) {
            Track chosen = pickLockInTrack(deadlineTracks, pace);
            List<TodayItem> items = toTodayItems(chosen,
                trackRepo.nextPendingItems(chosen.id(), remaining.get(chosen.id())));
            return new TodayPlan(items, mode, false);
        }

        // Normal mode (also the Lock-in fallback when nothing is on a deadline — nothing
        // to lock focus onto, so behave like Normal for that day).
        Map<Long, Double> reserved = new LinkedHashMap<>();
        for (Track t : deadlineTracks) reserved.put(t.id(), pace.get(t.id()) * todayCap);

        double reservedTotal = reserved.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean overBudget = reservedTotal > todayCap && trimByPriority(deadlineTracks, reserved, todayCap);

        List<TodayItem> items = new ArrayList<>();
        double used = 0;
        for (Track t : deadlineTracks) {
            long take = Math.min(remaining.get(t.id()), Math.round(reserved.getOrDefault(t.id(), 0.0)));
            if (take <= 0) continue;
            items.addAll(toTodayItems(t, trackRepo.nextPendingItems(t.id(), (int) take)));
            used += take;
        }

        double remainingCap = Math.max(0, todayCap - used);
        for (Track t : manualTracks) {
            Integer budget = trackRepo.getScheduleBudget(t.id(), weekday);
            if (budget == null || budget <= 0) continue;
            long take = Math.min(budget, Math.round(remainingCap));
            if (take <= 0) continue;
            items.addAll(toTodayItems(t, trackRepo.nextPendingItems(t.id(), (int) take)));
            remainingCap -= take;
        }

        return new TodayPlan(items, mode, overBudget);
    }

    /** Single-track focus, auto-picked — no manual rotation/recency state needed: a
     *  productive lock-in day burns down itemsRemaining, dropping that track's urgency
     *  below a rival's, so tomorrow's pick rotates on its own. */
    private Track pickLockInTrack(List<Track> deadlineTracks, Map<Long, Double> pace) {
        Track best = null;
        double bestUrgency = Double.NEGATIVE_INFINITY;
        for (Track t : deadlineTracks) {
            double urgency = priorityWeight(t.priority()) * pace.get(t.id());
            if (urgency > bestUrgency) {
                bestUrgency = urgency;
                best = t;
            }
        }
        return best;
    }

    /** must=3 / should=2 / could=1. Untagged priority (nullable — only meaningful once a
     *  deadline is set) defaults to 'should' weight: a middle value, not an accidental
     *  'must'. */
    private double priorityWeight(String priority) {
        if ("must".equals(priority)) return 3;
        if ("could".equals(priority)) return 1;
        return 2;
    }

    /** MoSCoW trim, mutating {@code reserved} in place: could first, then should, then —
     *  if they alone still exceed capacity — must itself gets pro-rata trimmed (Normal
     *  mode stays capacity-bounded even for musts; that event is what {@code overBudget}
     *  signals, not a silent drop). Returns true iff musts themselves were trimmed. */
    private boolean trimByPriority(List<Track> deadlineTracks, Map<Long, Double> reserved, double target) {
        List<Track> musts = byPriority(deadlineTracks, "must");
        List<Track> shoulds = byPriority(deadlineTracks, "should");
        List<Track> coulds = byPriority(deadlineTracks, "could");

        double mustSum = sumReserved(musts, reserved);
        if (mustSum > target) {
            double scale = mustSum <= 0 ? 0 : target / mustSum;
            for (Track t : musts) reserved.merge(t.id(), scale, (r, s) -> r * s);
            for (Track t : shoulds) reserved.put(t.id(), 0.0);
            for (Track t : coulds) reserved.put(t.id(), 0.0);
            return true;
        }

        double budgetLeft = target - mustSum;
        double shouldSum = sumReserved(shoulds, reserved);
        if (shouldSum <= budgetLeft) {
            budgetLeft -= shouldSum;
            double couldSum = sumReserved(coulds, reserved);
            if (couldSum > budgetLeft) {
                double scale = couldSum <= 0 ? 0 : budgetLeft / couldSum;
                for (Track t : coulds) reserved.merge(t.id(), scale, (r, s) -> r * s);
            }
        } else {
            double scale = shouldSum <= 0 ? 0 : budgetLeft / shouldSum;
            for (Track t : shoulds) reserved.merge(t.id(), scale, (r, s) -> r * s);
            for (Track t : coulds) reserved.put(t.id(), 0.0);
        }
        return false;
    }

    /** Untagged priority groups with 'should' — see {@link #priorityWeight}. */
    private List<Track> byPriority(List<Track> tracks, String priority) {
        return tracks.stream()
            .filter(t -> priority.equals(t.priority() == null ? "should" : t.priority()))
            .toList();
    }

    private double sumReserved(List<Track> tracks, Map<Long, Double> reserved) {
        return tracks.stream().mapToDouble(t -> reserved.getOrDefault(t.id(), 0.0)).sum();
    }

    /** Σ daily_capacity(d) for d in [fromInclusive, toExclusive) — the pace denominator. */
    private double capacityBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        double sum = 0;
        for (LocalDate d = fromInclusive; d.isBefore(toExclusive); d = d.plusDays(1)) {
            sum += trackRepo.getCapacity(weekdayOf(d));
        }
        return sum;
    }

    private int weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }

    private List<TodayItem> toTodayItems(Track track, List<TrackItem> items) {
        return items.stream()
            .map(i -> new TodayItem(i.id(), track.id(), track.title(), track.type(), i.title(), i.notePath()))
            .toList();
    }
}
