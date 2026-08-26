package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TodayPlanService.TodayPlan;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
    private final TrackProgressService progressService;
    private final TrackAgentClient trackAgentClient;
    private final SubscriptionPollWorker subscriptionPollWorker;

    TrackController(TrackRepository trackRepo, TodayPlanService todayPlan,
                    TrackReviewHandoff reviewHandoff, TrackProgressService progressService,
                    TrackAgentClient trackAgentClient, SubscriptionPollWorker subscriptionPollWorker) {
        this.trackRepo = trackRepo;
        this.todayPlan = todayPlan;
        this.reviewHandoff = reviewHandoff;
        this.progressService = progressService;
        this.trackAgentClient = trackAgentClient;
        this.subscriptionPollWorker = subscriptionPollWorker;
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
        return ResponseEntity.ok(trackRepo.create(req.title().trim(), type, "manual",
            req.sourceUrl(), req.sourceType()));
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

    /** Manual "check now" for a subscription track — bypasses the poll worker's interval
     *  gate and polls immediately. */
    @PostMapping("{id}/poll-now")
    public ResponseEntity<?> pollNow(@PathVariable long id) {
        return subscriptionPollWorker.pollNow(id)
            ? ResponseEntity.ok().build()
            : ResponseEntity.badRequest().body("not a subscription track");
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
    public TodayPlan today() {
        return todayPlan.today();
    }

    // ── Progress (Phase 1d) ────────────────────────────────────────────────────

    @GetMapping("progress")
    public List<TrackProgressService.TrackProgress> progress() {
        return progressService.progress();
    }

    // ── Capacity + mode (Phase 1c) ──────────────────────────────────────────────

    @GetMapping("capacity")
    public Map<Integer, Double> getCapacity() {
        return trackRepo.getCapacityMap();
    }

    /** Partial upsert — only the given weekdays change (weekday(string) -> capacity). */
    @PutMapping("capacity")
    public Map<Integer, Double> setCapacity(@RequestBody Map<String, Double> weekdayCapacities) {
        Map<Integer, Double> parsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Double> e : weekdayCapacities.entrySet()) {
            parsed.put(Integer.parseInt(e.getKey()), e.getValue());
        }
        trackRepo.setCapacity(parsed);
        return trackRepo.getCapacityMap();
    }

    @PutMapping("mode")
    public ResponseEntity<?> setMode(@RequestBody ModeRequest req) {
        if (!TodayPlanService.MODE_NORMAL.equals(req.mode()) && !TodayPlanService.MODE_LOCKIN.equals(req.mode())) {
            return ResponseEntity.badRequest().body("mode must be 'normal' or 'lockin'");
        }
        todayPlan.setMode(req.mode());
        return ResponseEntity.ok(Map.of("mode", req.mode()));
    }

    // ── Mini-course generation (Phase 2) ────────────────────────────────────────

    @PostMapping("{id}/minicourse")
    public ResponseEntity<String> generateMinicourse(@PathVariable long id) {
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        return toResponse(trackAgentClient.submitMinicourse(id));
    }

    @GetMapping("minicourse/{jobId}")
    public ResponseEntity<String> pollMinicourse(@PathVariable String jobId) {
        return toResponse(trackAgentClient.pollMinicourse(jobId));
    }

    @PostMapping("minicourse/{jobId}/approve")
    public ResponseEntity<String> approveMinicourse(@PathVariable String jobId,
                                                    @RequestBody(required = false) ApproveMinicourseRequest req) {
        List<Integer> approvedIndexes = req == null ? null : req.approvedIndexes();
        return toResponse(trackAgentClient.approveMinicourse(jobId, approvedIndexes));
    }

    private static ResponseEntity<String> toResponse(TrackAgentClient.Result res) {
        return ResponseEntity.status(res.status() == 0 ? 502 : res.status())
            .header("Content-Type", "application/json")
            .body(res.body());
    }

    // ── Excel import (Phase 3) ──────────────────────────────────────────────────

    @PostMapping("import")
    public ResponseEntity<String> importCsv(@RequestBody ImportCsvRequest req) {
        return toResponse(trackAgentClient.importCsv(req.csvText()));
    }

    /** Writes the (possibly user-edited) preview through the normal CRUD path — no
     *  embedder call here, the embedder only produced the preview mapping. */
    @PostMapping("import/commit")
    public ResponseEntity<?> commitImport(@RequestBody ImportCommitRequest req) {
        Map<Integer, Long> trackIdByIndex = new LinkedHashMap<>();
        for (int i = 0; i < req.tracks().size(); i++) {
            ImportedTrack t = req.tracks().get(i);
            String type = t.type() == null || t.type().isBlank() ? "custom" : t.type();
            Track created = trackRepo.create(t.title(), type, "excel_import");
            trackIdByIndex.put(i, created.id());
        }
        for (ImportedItem item : req.items()) {
            Long trackId = trackIdByIndex.get(item.trackIndex());
            if (trackId == null) continue;
            TrackItem created = trackRepo.addItem(trackId, item.title(), null);
            if ("done".equals(item.status())) {
                trackRepo.completeItem(created.id());
            }
        }
        return ResponseEntity.ok(Map.of("tracksCreated", trackIdByIndex.size(), "itemsCreated", req.items().size()));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateTrackRequest(String title, String type, String sourceUrl, String sourceType) {}
    record UpdateTrackRequest(String title, String type, String status, String deadline,
                              String priority, Boolean includeInProgress, Boolean clearDeadline) {}
    record AddItemRequest(String title, String notePath) {}
    record UpdateItemRequest(String title, Integer position) {}
    record CompleteItemRequest(Boolean addToReview) {}
    record ModeRequest(String mode) {}
    record ApproveMinicourseRequest(List<Integer> approvedIndexes) {}
    record ImportCsvRequest(String csvText) {}
    record ImportedTrack(String title, String type) {}
    record ImportedItem(int trackIndex, String title, String status) {}
    record ImportCommitRequest(List<ImportedTrack> tracks, List<ImportedItem> items) {}
}
