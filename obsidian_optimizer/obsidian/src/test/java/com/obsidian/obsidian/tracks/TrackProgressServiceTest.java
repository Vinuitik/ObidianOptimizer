package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackProgressService.TrackProgress;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackProgressRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Phase 1d — see the plan doc's Phase 1d section and tracks/FLOWS.md "Progress Flow" for
 * the onTrack formula: expectedDone = itemsTotal * (capacityElapsed / capacitySpan).
 */
@ExtendWith(MockitoExtension.class)
class TrackProgressServiceTest {

    @Mock TrackRepository trackRepo;
    TrackProgressService service;

    private Track track(long id, String title, LocalDate createdDate, LocalDate deadline,
                        boolean includeInProgress) {
        Instant createdAt = createdDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        return new Track(id, title, "book", "active", "manual", deadline, null, includeInProgress, createdAt,
            null, null, null);
    }

    @Test
    void noDeadline_onTrackIsNull() {
        service = new TrackProgressService(trackRepo);
        Track t = track(1, "Curiosities", LocalDate.of(2026, 8, 1), null, true);
        when(trackRepo.listProgressRows()).thenReturn(List.of(new TrackProgressRow(t, 2, 5)));

        List<TrackProgress> progress = service.progress(LocalDate.of(2026, 8, 17));

        assertThat(progress).hasSize(1);
        assertThat(progress.get(0).onTrack()).isNull();
        assertThat(progress.get(0).itemsDone()).isEqualTo(2);
        assertThat(progress.get(0).itemsTotal()).isEqualTo(5);
    }

    @Test
    void deadline_aheadOfExpectedPace_onTrackTrue() {
        service = new TrackProgressService(trackRepo);
        LocalDate created = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate deadline = LocalDate.of(2026, 9, 1);
        Track t = track(1, "Rust", created, deadline, true);
        when(trackRepo.listProgressRows()).thenReturn(List.of(new TrackProgressRow(t, 8, 10)));
        when(trackRepo.capacityBetween(created, deadline)).thenReturn(100.0); // full span
        when(trackRepo.capacityBetween(created, today)).thenReturn(40.0);     // 40% elapsed

        List<TrackProgress> progress = service.progress(today);

        // expectedDone = 10 * (40/100) = 4; itemsDone=8 >= 4 -> on track
        assertThat(progress.get(0).onTrack()).isTrue();
    }

    @Test
    void deadline_behindExpectedPace_onTrackFalse() {
        service = new TrackProgressService(trackRepo);
        LocalDate created = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate deadline = LocalDate.of(2026, 9, 1);
        Track t = track(1, "Rust", created, deadline, true);
        when(trackRepo.listProgressRows()).thenReturn(List.of(new TrackProgressRow(t, 1, 10)));
        when(trackRepo.capacityBetween(created, deadline)).thenReturn(100.0);
        when(trackRepo.capacityBetween(created, today)).thenReturn(40.0);

        List<TrackProgress> progress = service.progress(today);

        // expectedDone = 10 * (40/100) = 4; itemsDone=1 < 4 -> behind
        assertThat(progress.get(0).onTrack()).isFalse();
    }

    @Test
    void deadline_exactlyOnPace_onTrackTrue() {
        service = new TrackProgressService(trackRepo);
        LocalDate created = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate deadline = LocalDate.of(2026, 9, 1);
        Track t = track(1, "Rust", created, deadline, true);
        when(trackRepo.listProgressRows()).thenReturn(List.of(new TrackProgressRow(t, 4, 10)));
        when(trackRepo.capacityBetween(created, deadline)).thenReturn(100.0);
        when(trackRepo.capacityBetween(created, today)).thenReturn(40.0);

        List<TrackProgress> progress = service.progress(today);

        // itemsDone == expectedDone (4 == 4) -> onTrack is inclusive (>=)
        assertThat(progress.get(0).onTrack()).isTrue();
    }

    @Test
    void zeroCapacitySpan_expectedDoneIsFullTotal() {
        service = new TrackProgressService(trackRepo);
        LocalDate created = LocalDate.of(2026, 8, 17);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate deadline = LocalDate.of(2026, 8, 17); // deadline == created, span=0
        Track t = track(1, "Rush", created, deadline, true);
        when(trackRepo.listProgressRows()).thenReturn(List.of(new TrackProgressRow(t, 3, 10)));
        when(trackRepo.capacityBetween(created, deadline)).thenReturn(0.0);

        List<TrackProgress> progress = service.progress(today);

        // capacitySpan<=0 -> expectedDone falls back to itemsTotal (10); 3 < 10 -> behind
        assertThat(progress.get(0).onTrack()).isFalse();
    }

    /** Grouping (Subscriptions Step 4) is inert to progress. TrackProgressRow carries only
     *  the itemsDone/itemsTotal counts already aggregated by the repo's GROUP BY t.id —
     *  it has no groupId field at all, so a "some items grouped" and "same items ungrouped"
     *  fixture are represented by the identical row here; this proves the service's
     *  itemsDone/itemsTotal/onTrack computation has no way to vary with grouping. */
    @Test
    void itemGrouping_doesNotAffectProgressCounts() {
        service = new TrackProgressService(trackRepo);
        LocalDate created = LocalDate.of(2026, 8, 1);
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate deadline = LocalDate.of(2026, 9, 1);
        Track t = track(1, "Grouped", created, deadline, true);
        // Represents a track with 2 of 5 items sharing a group_id and 3 ungrouped —
        // itemsDone/itemsTotal are what the repo's GROUP BY t.id would produce either way.
        TrackProgressRow groupedRow = new TrackProgressRow(t, 2, 5);
        TrackProgressRow ungroupedRow = new TrackProgressRow(t, 2, 5);
        when(trackRepo.capacityBetween(created, deadline)).thenReturn(100.0);
        when(trackRepo.capacityBetween(created, today)).thenReturn(40.0);

        when(trackRepo.listProgressRows()).thenReturn(List.of(groupedRow));
        TrackProgress groupedResult = service.progress(today).get(0);

        when(trackRepo.listProgressRows()).thenReturn(List.of(ungroupedRow));
        TrackProgress ungroupedResult = service.progress(today).get(0);

        assertThat(groupedResult).isEqualTo(ungroupedResult);
    }
}
