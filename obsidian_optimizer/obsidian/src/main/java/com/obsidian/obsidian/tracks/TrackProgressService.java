package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackProgressRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Phase 1d — per-track completion for the Progress tab, plus an on-track/behind signal
 * for deadline tracks. Reuses {@link TrackRepository#capacityBetween} (the same pace
 * denominator Phase 1c's TodayPlanService uses) rather than tracking progress state
 * separately — see tracks/FLOWS.md "Progress Flow".
 */
@Service
public class TrackProgressService {

    private final TrackRepository trackRepo;

    public TrackProgressService(TrackRepository trackRepo) {
        this.trackRepo = trackRepo;
    }

    /** onTrack is null for tracks with no deadline (nothing to pace against). */
    public record TrackProgress(long id, String title, String type, int itemsDone,
                                int itemsTotal, LocalDate deadline, Boolean onTrack) {}

    public List<TrackProgress> progress() {
        return progress(LocalDate.now());
    }

    /** Date-parameterized for testability, same convention as TodayPlanService.today(LocalDate). */
    List<TrackProgress> progress(LocalDate today) {
        return trackRepo.listProgressRows().stream().map(row -> toProgress(row, today)).toList();
    }

    private TrackProgress toProgress(TrackProgressRow row, LocalDate today) {
        Track t = row.track();
        Boolean onTrack = null;
        if (t.deadline() != null) {
            LocalDate created = t.createdAt().atZone(ZoneId.systemDefault()).toLocalDate();
            double capacitySpan = trackRepo.capacityBetween(created, t.deadline());
            double capacityElapsed = trackRepo.capacityBetween(created, today);
            double expectedDone = capacitySpan > 0
                ? row.itemsTotal() * (capacityElapsed / capacitySpan)
                : row.itemsTotal();
            onTrack = row.itemsDone() >= expectedDone;
        }
        return new TrackProgress(t.id(), t.title(), t.type(), row.itemsDone(), row.itemsTotal(),
            t.deadline(), onTrack);
    }
}
