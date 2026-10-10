package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.feed.SourceRepository.Source;
import com.obsidian.obsidian.tracks.TrackAgentClient;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** REST for the Passive Feed (items + sources). Session-auth via the global filter chain. */
@RestController
@RequestMapping("feed")
public class FeedController {

    private final FeedRepository feedRepo;
    private final SourceRepository sourceRepo;
    private final FeedService feedService;
    private final SourcePollWorker pollWorker;
    private final TrackAgentClient trackAgentClient;

    public FeedController(FeedRepository feedRepo, SourceRepository sourceRepo, FeedService feedService,
                          SourcePollWorker pollWorker, TrackAgentClient trackAgentClient) {
        this.feedRepo = feedRepo;
        this.sourceRepo = sourceRepo;
        this.feedService = feedService;
        this.pollWorker = pollWorker;
        this.trackAgentClient = trackAgentClient;
    }

    public record CreateSourceRequest(String url, String title, Boolean autoAccept, Long targetTrackId) {}

    // ── Items ────────────────────────────────────────────────────────────────

    @GetMapping("items")
    public ResponseEntity<?> items(@RequestParam(defaultValue = "pending") String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        if (!List.of("pending", "binned", "accepted").contains(status)) {
            return ResponseEntity.badRequest().body("status must be pending|binned|accepted");
        }
        return ResponseEntity.ok(feedRepo.list(status, Math.max(1, Math.min(limit, 500))));
    }

    @PostMapping("items/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable long id) {
        String captureId = feedService.accept(id);
        return captureId == null ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(Map.of("captureId", captureId));
    }

    @PostMapping("items/{id}/bin")
    public ResponseEntity<?> bin(@PathVariable long id) {
        if (feedRepo.get(id) == null) return ResponseEntity.notFound().build();
        feedService.bin(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("items/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable long id) {
        if (feedRepo.get(id) == null) return ResponseEntity.notFound().build();
        try {
            feedService.restore(id);
        } catch (FeedService.CaptureStartedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Sources ──────────────────────────────────────────────────────────────

    @GetMapping("sources")
    public List<Source> sources() {
        return sourceRepo.list();
    }

    /** Resolve via the embedder's discover, store the source, insert the current feed contents
     *  as the pending backlog (never auto-accepted). Idempotent per url. */
    @PostMapping("sources")
    public ResponseEntity<?> createSource(@RequestBody CreateSourceRequest req) {
        if (req.url() == null || req.url().isBlank()) return ResponseEntity.badRequest().body("url is required");
        String url = req.url().trim();
        Source existing = sourceRepo.getByUrl(url);
        if (existing != null) return ResponseEntity.ok(existing);

        boolean yt = isYoutube(url);
        DiscoverResult res = trackAgentClient.discover(url, yt ? "youtube_channel" : "feed");
        if (!res.ok()) {
            return ResponseEntity.unprocessableEntity().body("could not read a feed from that URL");
        }
        String feedUrl = res.resolvedFeedUrl() != null ? res.resolvedFeedUrl() : url;
        String kind = (yt || isYoutube(feedUrl)) ? "youtube" : "rss";
        String title = (req.title() != null && !req.title().isBlank()) ? req.title().trim() : defaultTitle(url);
        Source s = sourceRepo.create(kind, url, feedUrl, title, req.autoAccept(), req.targetTrackId());
        pollWorker.ingestCandidates(s, res.candidates(), true);
        return ResponseEntity.ok(sourceRepo.get(s.id()));
    }

    @PatchMapping("sources/{id}")
    public ResponseEntity<?> patchSource(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (sourceRepo.get(id) == null) return ResponseEntity.notFound().build();
        Boolean auto = body.get("autoAccept") instanceof Boolean b ? b : null;
        Long track = body.get("targetTrackId") instanceof Number n ? n.longValue() : null;
        String status = body.get("status") instanceof String s ? s : null;
        if (status != null && !status.equals("active") && !status.equals("paused")) {
            return ResponseEntity.badRequest().body("status must be active|paused");
        }
        String title = body.get("title") instanceof String t && !t.isBlank() ? t.trim() : null;
        return ResponseEntity.ok(sourceRepo.update(id,
            auto, body.containsKey("autoAccept") && body.get("autoAccept") == null,
            track, body.containsKey("targetTrackId") && body.get("targetTrackId") == null,
            status, title));
    }

    @DeleteMapping("sources/{id}")
    public ResponseEntity<?> deleteSource(@PathVariable long id) {
        return sourceRepo.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("sources/{id}/poll")
    public ResponseEntity<?> poll(@PathVariable long id) {
        if (sourceRepo.get(id) == null) return ResponseEntity.notFound().build();
        pollWorker.pollNowAsync(id);
        return ResponseEntity.accepted().build();
    }

    private static boolean isYoutube(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null && (h.equalsIgnoreCase("youtu.be") || h.toLowerCase().endsWith("youtube.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Best-effort label (the embedder returns no feed title): @handle, else host. */
    static String defaultTitle(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.startsWith("/@")) return path.substring(1).split("/")[0];
            return u.getHost() == null ? url : u.getHost();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
