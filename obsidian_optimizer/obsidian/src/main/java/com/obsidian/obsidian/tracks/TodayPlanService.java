package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntToDoubleFunction;

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

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault;

    public static final String MODE_KEY = "trackMode";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_LOCKIN = "lockin";

    private final TrackRepository trackRepo;
    private final SettingsRepository settingsRepo;

    public TodayPlanService(TrackRepository trackRepo, SettingsRepository settingsRepo,
            com.obsidian.obsidian.vaults.ActiveVault activeVault) {
        this.activeVault = activeVault;
        this.trackRepo = trackRepo;
        this.settingsRepo = settingsRepo;
    }

    public record TodayItem(long itemId, long trackId, String trackTitle, String trackType,
                            String title, String notePath, String reason) {}

    /** {@code overBudget}: Normal mode's must-priority deadline tracks alone exceeded
     *  today's capacity and got pro-rata trimmed anyway (not a silent drop) — the signal
     *  the frontend uses to show a "you're behind — switch to Lock-in?" banner. Never set
     *  in Lock-in mode (there's no capacity ceiling to overflow). */
    public record TodayPlan(List<TodayItem> items, String mode, boolean overBudget,
                            int planned, int capacity,
                            List<TrackUnitService.Unit> units, int backlogCount) {
        public TodayPlan(List<TodayItem> items, String mode, boolean overBudget, int planned, int capacity) {
            this(items, mode, overBudget, planned, capacity, List.of(), 0);
        }

        /** Learn units picked by {@link TrackUnitService} (tracks/FLOWS.md "Units"). */
        public TodayPlan withUnits(TrackUnitService.UnitsResult u) {
            return new TodayPlan(items, mode, overBudget, planned, capacity, u.units(), u.backlogCount());
        }
    }

    private static String dueLabel(LocalDate date, LocalDate deadline) {
        if (deadline.isBefore(date)) return "Overdue";
        if (deadline.equals(date)) return "Due today";
        return "Due " + deadline.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    public String getMode() {
        return settingsRepo.getOrDefault(MODE_KEY, MODE_NORMAL);
    }

    public void setMode(String mode) {
        settingsRepo.set(MODE_KEY, mode);
    }

    public TodayPlan today() {
        return today(LocalDate.now());
    }

    /** One track's share of a day: {@code take} items, with the human reason. */
    record Alloc(Track track, int take, String reason) {}

    /** Result of {@link #allocate}: ordered per-track takes + the must-overflow flag. */
    record Allocation(List<Alloc> allocs, boolean overBudget) {}

    /** Date-parameterized for testability — 0=Mon..6=Sun, matching track_schedule.weekday
     *  and daily_capacity.weekday. */
    TodayPlan today(LocalDate date) {
        String mode = getMode();
        double todayCap = trackRepo.getCapacity(weekdayOf(date));
        List<Track> active = trackRepo.listActive(activeVault.id());
        Map<Long, Integer> pending = new HashMap<>();
        for (Track t : active) pending.put(t.id(), trackRepo.countPendingItems(t.id()));

        Allocation a = allocate(date, mode, todayCap, trackRepo::getCapacity, active, pending,
            trackRepo::getScheduleBudget);
        List<TodayItem> items = new ArrayList<>();
        for (Alloc al : a.allocs()) {
            items.addAll(toTodayItems(al.track(), trackRepo.nextPendingItems(al.track().id(), al.take()), al.reason()));
        }
        return new TodayPlan(items, mode, a.overBudget(), items.size(), (int) Math.round(todayCap));
    }

    /** {@code planned} = what the day shows as its target for this track; {@code done} = items
     *  actually completed that day. {@code count} == {@code planned} (kept for older clients). */
    public record WeekTrackCount(long trackId, String trackTitle, String trackType, int count,
                                 int done, int planned) {}

    /** {@code done}/{@code planned} = day totals over {@code items}. */
    public record WeekDay(LocalDate date, String weekday, int capacity, List<WeekTrackCount> items,
                          int done, int planned) {}

    /** 7-day view starting today (past days only appear when {@code start} is earlier). */
    public List<WeekDay> week() {
        return week(LocalDate.now(), LocalDate.now());
    }

    List<WeekDay> week(LocalDate start) {
        return week(start, start);   // tests / callers that treat `start` as "today"
    }

    /** Per day: past (< today) = actual completions only (planned == done, nothing is
     *  re-projected); today = done so far + what the allocator still wants (planned = done +
     *  remaining); future = allocator projection only, assuming nothing gets completed (each
     *  projected day consumes its items from a working pending count so a 3-item track doesn't
     *  repeat on all 7 days). Today's remaining already excludes done items (pending only). */
    public List<WeekDay> week(LocalDate start, LocalDate today) {
        String mode = getMode();
        long vid = activeVault.id();
        List<Track> active = trackRepo.listActive(vid);
        Map<Long, Integer> pending = new HashMap<>();
        for (Track t : active) pending.put(t.id(), trackRepo.countPendingItems(t.id()));

        LocalDate end = start.plusDays(7);
        LocalDate doneTo = end.isAfter(today.plusDays(1)) ? today.plusDays(1) : end;
        Map<LocalDate, Map<Long, Integer>> doneByDay =
            trackRepo.completionsByDay(vid, start, doneTo, java.time.ZoneId.systemDefault());
        Map<Long, Track> byId = new HashMap<>();
        for (Track t : trackRepo.listAll(vid)) byId.put(t.id(), t);

        List<WeekDay> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            double cap = trackRepo.getCapacity(weekdayOf(d));
            Map<Long, Integer> done = doneByDay.getOrDefault(d, Map.of());
            List<WeekTrackCount> counts = new ArrayList<>();
            int dayDone = 0, dayPlanned = 0;
            if (d.isBefore(today)) {
                for (Map.Entry<Long, Integer> e : done.entrySet()) {
                    Track t = byId.get(e.getKey());
                    if (t == null) continue;
                    counts.add(new WeekTrackCount(t.id(), t.title(), t.type(), e.getValue(), e.getValue(), e.getValue()));
                    dayDone += e.getValue(); dayPlanned += e.getValue();
                }
            } else {
                Allocation a = allocate(d, mode, cap, trackRepo::getCapacity, active, pending,
                    trackRepo::getScheduleBudget);
                boolean isToday = d.equals(today);
                Map<Long, Integer> remaining = new LinkedHashMap<>();
                for (Alloc al : a.allocs()) {
                    if (al.take() <= 0) continue;
                    pending.merge(al.track().id(), -al.take(), Integer::sum);
                    remaining.put(al.track().id(), al.take());
                }
                if (isToday) {
                    for (Long tid : done.keySet()) remaining.putIfAbsent(tid, 0);
                }
                for (Map.Entry<Long, Integer> e : remaining.entrySet()) {
                    Track t = byId.get(e.getKey());
                    if (t == null) {   // allocator tracks are always active tracks of this vault
                        t = active.stream().filter(x -> x.id() == e.getKey()).findFirst().orElse(null);
                        if (t == null) continue;
                    }
                    int dn = isToday ? done.getOrDefault(e.getKey(), 0) : 0;
                    int planned = dn + e.getValue();
                    counts.add(new WeekTrackCount(t.id(), t.title(), t.type(), planned, dn, planned));
                    dayDone += dn; dayPlanned += planned;
                }
            }
            days.add(new WeekDay(d, d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                (int) Math.round(cap), counts, dayDone, dayPlanned));
        }
        return days;
    }

    /** Pure allocator shared by Today and Week — no repository access beyond the passed
     *  lookups, no mutation of {@code pending}. Order: deadline tracks (capacity-weighted
     *  pace), then manual tracks with a track_schedule budget, then unscheduled no-deadline
     *  tracks greedily by priority (must>should>could, stable on active-list order) until
     *  today's capacity is spent. Lock-in with >=1 pending deadline track = single-track focus. */
    static Allocation allocate(LocalDate date, String mode, double todayCap,
                               IntToDoubleFunction capacityOf, List<Track> active,
                               Map<Long, Integer> pending,
                               BiFunction<Long, Integer, Integer> scheduleBudget) {
        int weekday = weekdayOf(date);
        List<Track> deadlineTracks = new ArrayList<>();
        List<Track> manualTracks = new ArrayList<>();
        for (Track t : active) {
            if (t.deadline() != null) deadlineTracks.add(t); else manualTracks.add(t);
        }

        Map<Long, Double> pace = new HashMap<>();
        deadlineTracks.removeIf(t -> {
            int n = pending.getOrDefault(t.id(), 0);
            if (n <= 0) return true;
            double capSpan = 0;
            for (LocalDate d = date; d.isBefore(t.deadline()); d = d.plusDays(1)) {
                capSpan += capacityOf.applyAsDouble(weekdayOf(d));
            }
            // Deadline today/past (capSpan<=0): nothing left to spread it over, so the
            // track wants everything NOW - treat pace as "all remaining items, today".
            pace.put(t.id(), capSpan > 0 ? n / capSpan : n);
            return false;
        });

        if (MODE_LOCKIN.equals(mode) && !deadlineTracks.isEmpty()) {
            Track chosen = pickLockInTrack(deadlineTracks, pace);
            return new Allocation(List.of(new Alloc(chosen, pending.get(chosen.id()),
                "Focus: most urgent deadline · " + dueLabel(date, chosen.deadline()))), false);
        }

        // Normal mode (also the Lock-in fallback when nothing is on a deadline).
        Map<Long, Double> reserved = new LinkedHashMap<>();
        for (Track t : deadlineTracks) reserved.put(t.id(), pace.get(t.id()) * todayCap);

        double reservedTotal = reserved.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean overBudget = reservedTotal > todayCap && trimByPriority(deadlineTracks, reserved, todayCap);

        List<Alloc> out = new ArrayList<>();
        double used = 0;
        for (Track t : deadlineTracks) {
            long take = Math.min(pending.get(t.id()), Math.round(reserved.getOrDefault(t.id(), 0.0)));
            if (take <= 0) continue;
            String due = dueLabel(date, t.deadline());
            boolean trimmed = overBudget && "must".equals(t.priority());
            String reason = trimmed
                ? due + " · trimmed: must deadlines exceed today's capacity"
                : due + " · ~" + take + "/day to finish";
            out.add(new Alloc(t, (int) take, reason));
            used += take;
        }

        double remainingCap = Math.max(0, todayCap - used);
        List<Track> unscheduled = new ArrayList<>();
        for (Track t : manualTracks) {
            int left = pending.getOrDefault(t.id(), 0);
            if (left <= 0) continue;
            Integer budget = scheduleBudget.apply(t.id(), weekday);
            if (budget == null || budget <= 0) { unscheduled.add(t); continue; }
            long take = Math.min(Math.min(budget, left), Math.round(remainingCap));
            if (take <= 0) continue;
            out.add(new Alloc(t, (int) take, "Fills leftover capacity"));
            remainingCap -= take;
        }

        // Fallback: no schedule entry + no deadline -> soak up whatever capacity is left,
        // must>should>could; List.sort is stable so ties keep active-list (newest-first) order.
        unscheduled.sort((x, y) -> Double.compare(priorityWeight(y.priority()), priorityWeight(x.priority())));
        for (Track t : unscheduled) {
            long take = Math.min(pending.get(t.id()), Math.round(remainingCap));
            if (take <= 0) continue;
            out.add(new Alloc(t, (int) take, "Fills leftover capacity"));
            remainingCap -= take;
        }

        return new Allocation(out, overBudget);
    }

    /** Single-track focus, auto-picked — no manual rotation/recency state needed: a
     *  productive lock-in day burns down itemsRemaining, dropping that track's urgency
     *  below a rival's, so tomorrow's pick rotates on its own. */
    private static Track pickLockInTrack(List<Track> deadlineTracks, Map<Long, Double> pace) {
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
    private static double priorityWeight(String priority) {
        if ("must".equals(priority)) return 3;
        if ("could".equals(priority)) return 1;
        return 2;
    }

    /** MoSCoW trim, mutating {@code reserved} in place: could first, then should, then —
     *  if they alone still exceed capacity — must itself gets pro-rata trimmed (Normal
     *  mode stays capacity-bounded even for musts; that event is what {@code overBudget}
     *  signals, not a silent drop). Returns true iff musts themselves were trimmed. */
    private static boolean trimByPriority(List<Track> deadlineTracks, Map<Long, Double> reserved, double target) {
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
    private static List<Track> byPriority(List<Track> tracks, String priority) {
        return tracks.stream()
            .filter(t -> priority.equals(t.priority() == null ? "should" : t.priority()))
            .toList();
    }

    private static double sumReserved(List<Track> tracks, Map<Long, Double> reserved) {
        return tracks.stream().mapToDouble(t -> reserved.getOrDefault(t.id(), 0.0)).sum();
    }

    private static int weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }

    private List<TodayItem> toTodayItems(Track track, List<TrackItem> items, String reason) {
        return items.stream()
            .map(i -> new TodayItem(i.id(), track.id(), track.title(), track.type(), i.title(), i.notePath(), reason))
            .toList();
    }
}
