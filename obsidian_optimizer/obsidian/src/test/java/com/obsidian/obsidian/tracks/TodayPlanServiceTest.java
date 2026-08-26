package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TodayPlanService.TodayItem;
import com.obsidian.obsidian.tracks.TodayPlanService.TodayPlan;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1c scenarios — see the plan doc's Phase 1c section and tracks/FLOWS.md's Today
 * Flow for the algorithm and the two design decisions (Lock-in = single-track focus,
 * Normal-mode must-overflow still capacity-bounded + banner) these tests hold to.
 */
@ExtendWith(MockitoExtension.class)
class TodayPlanServiceTest {

    @Mock TrackRepository trackRepo;
    @Mock SettingsRepository settingsRepo;
    TodayPlanService service;

    @BeforeEach
    void setUp() {
        service = new TodayPlanService(trackRepo, settingsRepo);
        lenient().when(settingsRepo.getOrDefault(TodayPlanService.MODE_KEY, TodayPlanService.MODE_NORMAL))
            .thenReturn(TodayPlanService.MODE_NORMAL);
    }

    private Track deadlineTrack(long id, String title, LocalDate deadline, String priority) {
        return new Track(id, title, "book", "active", "manual", deadline, priority, true, Instant.now(),
            null, null, null);
    }

    private Track manualTrack(long id, String title) {
        return new Track(id, title, "book", "active", "manual", null, null, true, Instant.now(), null, null, null);
    }

    /** Stubs nextPendingItems(trackId, n) to hand back n synthetic items, so item COUNT is
     *  what tests assert on rather than exact item identity. */
    private void stubItems(long trackId) {
        lenient().when(trackRepo.nextPendingItems(eq(trackId), anyInt())).thenAnswer(inv -> {
            int n = inv.getArgument(1);
            List<TrackItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                items.add(new TrackItem(trackId * 1000 + i, trackId, i, "item" + i, null, "pending", null, null));
            }
            return items;
        });
    }

    private long countFor(TodayPlan plan, long trackId) {
        return plan.items().stream().filter(i -> i.trackId() == trackId).count();
    }

    // ── 1. Pace recompute — not stale across reads ──────────────────────────────

    @Test
    void paceRecomputesFreshEachRead_notStale() {
        LocalDate mon = LocalDate.of(2026, 8, 17); // Monday
        LocalDate deadline = mon.plusDays(5); // window = Mon..Fri
        Track t = deadlineTrack(1, "Rust", deadline, "should");
        when(trackRepo.listActive()).thenReturn(List.of(t));
        stubItems(1);
        // Monday capacity=2 (today's reserve multiplier), Tue-Fri=1 each (pace denominator).
        when(trackRepo.getCapacity(0)).thenReturn(2.0); // Mon
        when(trackRepo.getCapacity(1)).thenReturn(1.0); // Tue
        when(trackRepo.getCapacity(2)).thenReturn(1.0); // Wed
        when(trackRepo.getCapacity(3)).thenReturn(1.0); // Thu
        when(trackRepo.getCapacity(4)).thenReturn(1.0); // Fri

        when(trackRepo.countPendingItems(1)).thenReturn(5);
        TodayPlan monday = service.today(mon);
        // window=capacityBetween(Mon,Sat)=2+1+1+1+1=6; pace=5/6=0.833; reserved=0.833*2=1.667 -> round 2
        assertThat(monday.items()).hasSize(2);

        LocalDate tue = mon.plusDays(1);
        when(trackRepo.countPendingItems(1)).thenReturn(3); // 2 completed since Monday's read
        TodayPlan tuesday = service.today(tue);
        // window=capacityBetween(Tue,Sat)=1+1+1+1=4; pace=3/4=0.75; reserved=0.75*1=0.75 -> round 1
        assertThat(tuesday.items()).hasSize(1);
        // A stale/cached computation would still show Monday's answer (2) — it doesn't.
        assertThat(tuesday.items()).hasSizeLessThan(monday.items().size());
    }

    // ── 2. Weekend pull — Saturday reserves more than a weekday, no per-track config ──

    @Test
    void weekendCapacity_pullsProportionallyMoreThanWeekday() {
        LocalDate mon = LocalDate.of(2026, 8, 17);
        LocalDate deadlineNextMon = mon.plusDays(7); // window spans a full week incl. one Sat/Sun
        Track t = deadlineTrack(1, "Big Book", deadlineNextMon, "should");
        when(trackRepo.listActive()).thenReturn(List.of(t));
        stubItems(1);
        when(trackRepo.countPendingItems(1)).thenReturn(1000); // large so reserve, not remaining, binds
        // Default seed shape: weekday=4, Sat/Sun=6.
        when(trackRepo.getCapacity(anyInt())).thenAnswer(inv -> {
            int wd = inv.getArgument(0);
            return (wd == 5 || wd == 6) ? 6.0 : 4.0;
        });

        TodayPlan weekdayPlan = service.today(mon); // Monday
        TodayPlan satPlan = service.today(mon.plusDays(5)); // Saturday, same track/deadline shape

        assertThat(countFor(satPlan, 1)).isGreaterThan(countFor(weekdayPlan, 1));
    }

    // ── 3. MoSCoW trim order — could first, then should, must preserved ─────────

    @Test
    void moscowTrim_couldFirstThenShould_mustPreserved() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate tomorrow = today.plusDays(1); // window = exactly today's capacity
        Track must = deadlineTrack(1, "Must", tomorrow, "must");
        Track should = deadlineTrack(2, "Should", tomorrow, "should");
        Track could = deadlineTrack(3, "Could", tomorrow, "could");
        when(trackRepo.listActive()).thenReturn(List.of(must, should, could));
        stubItems(1); stubItems(2); stubItems(3);
        when(trackRepo.getCapacity(anyInt())).thenReturn(10.0); // window==10, so reserved==remaining exactly
        when(trackRepo.countPendingItems(1)).thenReturn(5);
        when(trackRepo.countPendingItems(2)).thenReturn(4);
        when(trackRepo.countPendingItems(3)).thenReturn(3); // total 12 > capacity 10

        TodayPlan plan = service.today(today);

        assertThat(countFor(plan, 1)).isEqualTo(5); // must: fully preserved
        assertThat(countFor(plan, 2)).isEqualTo(4); // should: fully preserved
        assertThat(countFor(plan, 3)).isEqualTo(1); // could: trimmed from 3 to fit leftover (10-5-4=1)
        assertThat(plan.overBudget()).isFalse(); // musts were never touched — no banner
    }

    @Test
    void moscowTrim_mustOverflow_proRataTrimsMustsAndSetsOverBudget() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate tomorrow = today.plusDays(1);
        Track must1 = deadlineTrack(1, "Must1", tomorrow, "must");
        Track must2 = deadlineTrack(2, "Must2", tomorrow, "must");
        when(trackRepo.listActive()).thenReturn(List.of(must1, must2));
        stubItems(1); stubItems(2);
        when(trackRepo.getCapacity(anyInt())).thenReturn(10.0);
        when(trackRepo.countPendingItems(1)).thenReturn(8);
        when(trackRepo.countPendingItems(2)).thenReturn(8); // mustSum=16 > capacity=10

        TodayPlan plan = service.today(today);

        // scale = 10/16 = 0.625 -> each must's 8 reserved -> 5
        assertThat(countFor(plan, 1)).isEqualTo(5);
        assertThat(countFor(plan, 2)).isEqualTo(5);
        assertThat(plan.overBudget()).isTrue(); // triggers the "you're behind" banner
    }

    // ── 4. Manual fills leftover only, never pushes past capacity ───────────────

    @Test
    void manualTracks_fillOnlyLeftoverCapacity() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate tomorrow = today.plusDays(1);
        Track deadline = deadlineTrack(1, "Deadline", tomorrow, "should");
        Track manual = manualTrack(2, "Manual");
        when(trackRepo.listActive()).thenReturn(List.of(deadline, manual));
        stubItems(1); stubItems(2);
        when(trackRepo.getCapacity(anyInt())).thenReturn(10.0);
        when(trackRepo.countPendingItems(1)).thenReturn(7); // reserved == 7 (window==capacity trick)
        when(trackRepo.getScheduleBudget(2, 0)).thenReturn(5); // wants 5, only 3 left

        TodayPlan plan = service.today(today);

        assertThat(countFor(plan, 1)).isEqualTo(7);
        assertThat(countFor(plan, 2)).isEqualTo(3); // capped at remainingCap (10-7), not the full budget of 5
        assertThat(plan.items()).hasSize(10); // never exceeds todayCap
    }

    // ── 5. Lock-in bypass — single-track focus, fully uncapped ──────────────────

    @Test
    void lockInMode_singleHighestUrgencyTrack_uncappedByCapacity() {
        when(settingsRepo.getOrDefault(TodayPlanService.MODE_KEY, TodayPlanService.MODE_NORMAL))
            .thenReturn(TodayPlanService.MODE_LOCKIN);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate tomorrow = today.plusDays(1);
        Track must = deadlineTrack(1, "Must", tomorrow, "must");   // pace 5/10=0.5, urgency 3*0.5=1.5
        Track should = deadlineTrack(2, "Should", tomorrow, "should"); // pace 4/10=0.4, urgency 2*0.4=0.8
        Track could = deadlineTrack(3, "Could", tomorrow, "could");    // pace 3/10=0.3, urgency 1*0.3=0.3
        when(trackRepo.listActive()).thenReturn(List.of(must, should, could));
        stubItems(1); // lock-in only ever pulls items for the ONE chosen track
        when(trackRepo.getCapacity(anyInt())).thenReturn(10.0); // would only allow ~10 items total in Normal mode
        when(trackRepo.countPendingItems(1)).thenReturn(5);
        when(trackRepo.countPendingItems(2)).thenReturn(4);
        when(trackRepo.countPendingItems(3)).thenReturn(3);

        TodayPlan plan = service.today(today);

        assertThat(plan.mode()).isEqualTo(TodayPlanService.MODE_LOCKIN);
        assertThat(countFor(plan, 1)).isEqualTo(5); // ALL of must's remaining items, uncapped
        assertThat(countFor(plan, 2)).isZero(); // no other track mixed in — single-track focus
        assertThat(countFor(plan, 3)).isZero();
        assertThat(plan.overBudget()).isFalse(); // no capacity ceiling in lock-in, nothing to overflow
    }

    // ── 6. No-deadline tracks — regression guard vs. Phase 1's flat allocation ──

    @Test
    void noDeadlineTracks_matchesPhase1FlatAllocation() {
        LocalDate mon = LocalDate.of(2026, 8, 17);
        Track a = manualTrack(1, "A");
        Track b = manualTrack(2, "B");
        when(trackRepo.listActive()).thenReturn(List.of(a, b));
        stubItems(1); stubItems(2);
        when(trackRepo.getCapacity(0)).thenReturn(4.0); // default weekday capacity — plenty of headroom
        when(trackRepo.getScheduleBudget(1, 0)).thenReturn(2);
        when(trackRepo.getScheduleBudget(2, 0)).thenReturn(2);

        TodayPlan plan = service.today(mon);

        // Phase 1 flat allocation would give exactly budget-many items per scheduled track.
        assertThat(countFor(plan, 1)).isEqualTo(2);
        assertThat(countFor(plan, 2)).isEqualTo(2);
        assertThat(plan.overBudget()).isFalse();
    }

    // ── 7 covered at the repository level (TrackRepositoryIT: capacity seed + partial edit) ──

    // ── 8. Mode toggle delegates to the same persisted-settings mechanism as everything else ──

    @Test
    void modeToggle_readsAndWritesThroughSettingsRepository() {
        service.setMode(TodayPlanService.MODE_LOCKIN);
        verify(settingsRepo).set(TodayPlanService.MODE_KEY, TodayPlanService.MODE_LOCKIN);

        when(settingsRepo.getOrDefault(TodayPlanService.MODE_KEY, TodayPlanService.MODE_NORMAL))
            .thenReturn(TodayPlanService.MODE_LOCKIN);
        assertThat(service.getMode()).isEqualTo(TodayPlanService.MODE_LOCKIN);
        // Persistence itself (survives restart) is SettingsRepository's existing, already-
        // tested app_settings behavior — nothing new to re-verify at IT level here.
    }

    // ── 9. Item grouping (Subscriptions Step 4) is inert to Today allocation ────

    /** Grouping is pure display/provenance clustering — TodayItem has no groupId field, so
     *  this proves item.groupId() can never leak into or influence what Today allocates:
     *  two fixtures that differ ONLY in groupId produce byte-identical plans. */
    @Test
    void itemGrouping_doesNotAffectTodayAllocation() {
        LocalDate mon = LocalDate.of(2026, 8, 17); // Monday, weekday=0
        List<TrackItem> grouped = List.of(
            new TrackItem(101, 1, 0, "item0", null, "pending", null, 900L),
            new TrackItem(102, 1, 1, "item1", null, "pending", null, 900L),
            new TrackItem(103, 1, 2, "item2", null, "pending", null, null),
            new TrackItem(104, 1, 3, "item3", null, "pending", null, null),
            new TrackItem(105, 1, 4, "item4", null, "pending", null, null));
        List<TrackItem> ungrouped = grouped.stream()
            .map(i -> new TrackItem(i.id(), i.trackId(), i.position(), i.title(), i.notePath(),
                i.status(), i.completedAt(), null))
            .toList();

        TodayPlan groupedPlan = todayWithFixedItems(mon, grouped);
        TodayPlan ungroupedPlan = todayWithFixedItems(mon, ungrouped);

        assertThat(groupedPlan).isEqualTo(ungroupedPlan);
        assertThat(groupedPlan.items()).hasSize(5);
    }

    private TodayPlan todayWithFixedItems(LocalDate date, List<TrackItem> items) {
        TrackRepository repo = mock(TrackRepository.class);
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getOrDefault(TodayPlanService.MODE_KEY, TodayPlanService.MODE_NORMAL))
            .thenReturn(TodayPlanService.MODE_NORMAL);
        Track t = manualTrack(1, "Grouped Track");
        when(repo.listActive()).thenReturn(List.of(t));
        when(repo.getCapacity(anyInt())).thenReturn(10.0);
        when(repo.getScheduleBudget(1, 0)).thenReturn(items.size());
        when(repo.nextPendingItems(eq(1L), anyInt())).thenReturn(items);
        return new TodayPlanService(repo, settings).today(date);
    }
}
