package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TodayPlanService.TodayItem;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** REST for Learning Tracks — session-auth via the global filter chain (SecurityConfig),
 *  same as {@code cards}/{@code capture} controllers (no per-controller annotation needed). */
@RestController
@RequestMapping("tracks")
public class TrackController {

    private final TrackRepository trackRepo;
    private final TodayPlanService todayPlan;
    private final TrackReviewHandoff reviewHandoff;

    TrackController(TrackRepository trackRepo, TodayPlanService todayPlan,
                    TrackReviewHandoff reviewHandoff) {
        this.trackRepo = trackRepo;
        this.todayPlan = todayPlan;
        this.reviewHandoff = reviewHandoff;
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    @GetMapping
    public List<Track> list() {
        return trackRepo.listAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateTrackRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        String type = req.type() == null || req.type().isBlank() ? "custom" : req.type();
        return ResponseEntity.ok(trackRepo.create(req.title().trim(), type, "manual"));
    }

    @PatchMapping("{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody UpdateTrackRequest req) {
        Track existing = trackRepo.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        LocalDate deadline = null;
        boolean clearDeadline = false;
        if (req.clearDeadline() != null && req.clearDeadline()) {
            clearDeadline = true;
        } else if (req.deadline() != null) {
            deadline = LocalDate.parse(req.deadline());
        }
        return ResponseEntity.ok(trackRepo.update(id, req.title(), req.type(), req.status(),
            deadline, req.priority(), req.includeInProgress(), clearDeadline));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        trackRepo.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @GetMapping("{id}/items")
    public ResponseEntity<?> items(@PathVariable long id) {
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(trackRepo.listItems(id));
    }

    @PostMapping("{id}/items")
    public ResponseEntity<?> addItem(@PathVariable long id, @RequestBody AddItemRequest req) {
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        return ResponseEntity.ok(trackRepo.addItem(id, req.title().trim(), req.notePath()));
    }

    @PatchMapping("items/{itemId}")
    public ResponseEntity<?> updateItem(@PathVariable long itemId, @RequestBody UpdateItemRequest req) {
        TrackItem existing = trackRepo.getItem(itemId);
        if (existing == null) return ResponseEntity.notFound().build();
        TrackItem updated = existing;
        if (req.title() != null && !req.title().isBlank()) {
            updated = trackRepo.updateItemTitle(itemId, req.title().trim());
        }
        if (req.position() != null) {
            updated = trackRepo.reorderItem(itemId, req.position());
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("items/{itemId}")
    public ResponseEntity<?> deleteItem(@PathVariable long itemId) {
        trackRepo.deleteItem(itemId);
        return ResponseEntity.ok().build();
    }

    /** Mark an item done, optionally handing its note off into the FSRS review pool
     *  (see TrackReviewHandoff — reuses the existing seeding path, no new scheduling math). */
    @PostMapping("items/{itemId}/complete")
    public ResponseEntity<?> completeItem(@PathVariable long itemId,
                                          @RequestBody(required = false) CompleteItemRequest req) {
        TrackItem item = trackRepo.getItem(itemId);
        if (item == null) return ResponseEntity.notFound().build();
        boolean flipped = trackRepo.completeItem(itemId);
        boolean addToReview = req != null && Boolean.TRUE.equals(req.addToReview());
        if (flipped && addToReview && item.notePath() != null && !item.notePath().isBlank()) {
            reviewHandoff.seedDueToday(item.notePath());
        }
        return ResponseEntity.ok(trackRepo.getItem(itemId));
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    @GetMapping("{id}/schedule")
    public ResponseEntity<?> getSchedule(@PathVariable long id) {
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(trackRepo.getSchedule(id));
    }

    @PutMapping("{id}/schedule")
    public ResponseEntity<?> setSchedule(@PathVariable long id, @RequestBody Map<String, Integer> weekdayBudgets) {
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        Map<Integer, Integer> parsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : weekdayBudgets.entrySet()) {
            parsed.put(Integer.parseInt(e.getKey()), e.getValue());
        }
        trackRepo.setSchedule(id, parsed);
        return ResponseEntity.ok(trackRepo.getSchedule(id));
    }

    // ── Today ────────────────────────────────────────────────────────────────

    @GetMapping("today")
    public List<TodayItem> today() {
        return todayPlan.today();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateTrackRequest(String title, String type) {}
    record UpdateTrackRequest(String title, String type, String status, String deadline,
                              String priority, Boolean includeInProgress, Boolean clearDeadline) {}
    record AddItemRequest(String title, String notePath) {}
    record UpdateItemRequest(String title, Integer position) {}
    record CompleteItemRequest(Boolean addToReview) {}
}
