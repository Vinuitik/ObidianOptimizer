package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure read-time computation of "what's on Today" — no persisted daily-plan table,
 * no {@code @Scheduled} job. An unfinished item just stays {@code pending} and
 * resurfaces tomorrow; carryover is emergent, not stored (same principle as
 * {@code pwa/reviewPlan.js}'s {@code allocateTracks()}).
 *
 * Phase 1 ships the flat version below: for today's weekday, every active track with
 * a {@code track_schedule} entry contributes its next {@code daily_item_budget} pending
 * items. Phase 1c replaces this method's body with the capacity/deadline/MoSCoW-aware
 * version — same signature, same no-persisted-plan principle, richer allocation.
 */
@Service
public class TodayPlanService {

    private final TrackRepository trackRepo;

    public TodayPlanService(TrackRepository trackRepo) {
        this.trackRepo = trackRepo;
    }

    public record TodayItem(long itemId, long trackId, String trackTitle, String trackType,
                            String title, String notePath) {}

    public List<TodayItem> today() {
        return today(LocalDate.now());
    }

    /** Weekday-parameterized for testability — 0=Mon..6=Sun, matching track_schedule.weekday. */
    List<TodayItem> today(LocalDate date) {
        int weekday = date.getDayOfWeek().getValue() - 1;
        List<TodayItem> result = new ArrayList<>();
        for (Track track : trackRepo.listActive()) {
            Integer budget = trackRepo.getScheduleBudget(track.id(), weekday);
            if (budget == null || budget <= 0) continue;
            for (TrackItem item : trackRepo.nextPendingItems(track.id(), budget)) {
                result.add(new TodayItem(item.id(), track.id(), track.title(), track.type(),
                    item.title(), item.notePath()));
            }
        }
        return result;
    }
}
