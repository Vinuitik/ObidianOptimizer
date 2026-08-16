package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TodayPlanService.TodayItem;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Phase 1's flat allocation: for today's weekday, every active track with a
 *  track_schedule entry contributes its next daily_item_budget pending items. */
@ExtendWith(MockitoExtension.class)
class TodayPlanServiceTest {

    @Mock TrackRepository trackRepo;
    TodayPlanService service;

    // 2026-08-17 is a Monday → weekday 0.
    final LocalDate monday = LocalDate.of(2026, 8, 17);

    @BeforeEach
    void setUp() {
        service = new TodayPlanService(trackRepo);
    }

    private static Track track(long id, String title) {
        return new Track(id, title, "book", "active", "manual", null, null, true, Instant.now());
    }

    private static TrackItem item(long id, long trackId, int position, String title) {
        return new TrackItem(id, trackId, position, title, null, "pending", null);
    }

    @Test
    void pullsBudgetItemsFromEachScheduledTrack() {
        Track a = track(1, "Rust Book");
        Track b = track(2, "Algorithms");
        when(trackRepo.listActive()).thenReturn(List.of(a, b));
        when(trackRepo.getScheduleBudget(1, 0)).thenReturn(2);
        when(trackRepo.getScheduleBudget(2, 0)).thenReturn(1);
        when(trackRepo.nextPendingItems(1, 2)).thenReturn(List.of(
            item(10, 1, 0, "Ch1"), item(11, 1, 1, "Ch2")));
        when(trackRepo.nextPendingItems(2, 1)).thenReturn(List.of(item(20, 2, 0, "Sort")));

        List<TodayItem> items = service.today(monday);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(TodayItem::title).containsExactly("Ch1", "Ch2", "Sort");
        assertThat(items).extracting(TodayItem::trackTitle)
            .containsExactly("Rust Book", "Rust Book", "Algorithms");
    }

    @Test
    void unscheduledTrackContributesNothing() {
        Track a = track(1, "Rust Book");
        when(trackRepo.listActive()).thenReturn(List.of(a));
        when(trackRepo.getScheduleBudget(1, 0)).thenReturn(null); // no schedule entry for Monday

        List<TodayItem> items = service.today(monday);

        assertThat(items).isEmpty();
        verify(trackRepo, never()).nextPendingItems(eq(1L), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void zeroBudgetContributesNothing() {
        Track a = track(1, "Rust Book");
        when(trackRepo.listActive()).thenReturn(List.of(a));
        when(trackRepo.getScheduleBudget(1, 0)).thenReturn(0);

        List<TodayItem> items = service.today(monday);

        assertThat(items).isEmpty();
    }

    @Test
    void exhaustedTrackContributesFewerThanBudget() {
        Track a = track(1, "Rust Book");
        when(trackRepo.listActive()).thenReturn(List.of(a));
        when(trackRepo.getScheduleBudget(1, 0)).thenReturn(3);
        // Only one pending item left even though budget is 3 — repo naturally returns fewer.
        when(trackRepo.nextPendingItems(1, 3)).thenReturn(List.of(item(10, 1, 0, "Last chapter")));

        List<TodayItem> items = service.today(monday);

        assertThat(items).hasSize(1);
    }
}
