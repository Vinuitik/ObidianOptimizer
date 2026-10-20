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

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault;

    private final FeedRepository feedRepo;
    private final SourceRepository sourceRepo;
    private final FeedService feedService;
    private final SourcePollWorker pollWorker;
    private final TrackAgentClient trackAgentClient;

    public FeedController(FeedRepository feedRepo, SourceRepository sourceRepo, FeedService feedService,
                          SourcePollWorker pollWorker, TrackAgentClient trackAgentClient,
            com.obsidian.obsidian.vaults.ActiveVault activeVault) {
        this.activeVault = activeVault;
        this.feedRepo = feedRepo;
        this.sourceRepo = sourceRepo;
        this.feedService = feedService;
        this.pollWorker = pollWorker;
        this.trackAgentClient = trackAgentClient;
    }

    public record CreateSourceRequest(String url, String title, Boolean autoAccept) {}

    // ── Items ────────────────────────────────────────────────────────────────

    @GetMapping("items")
    public ResponseEntity<?> items(@RequestParam(defaultValue = "pending") String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        if (!List.of("pending", "binned", "accepted").contains(status)) {
            return ResponseEntity.badRequest().body("status must be pending|binned|accepted");
        }
        return ResponseEntity.ok(feedRepo.list(activeVault.id(), status, Math.max(1, Math.min(limit, 500))));
    }

    @PostMapping("items/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable long id) {
        String captureId = feedService.accept(activeVault.id(), id);
        return captureId == null ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(Map.of("captureId", captureId));
    }

    @PostMapping("items/{id}/bin")
    public ResponseEntity<?> bin(@PathVariable long id) {
        if (feedRepo.get(activeVault.id(), id) == null) return ResponseEntity.notFound().build();
        feedService.bin(activeVault.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("items/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable long id) {
        if (feedRepo.get(activeVault.id(), id) == null) return ResponseEntity.notFound().build();
        try {
            feedService.restore(activeVault.id(), id);
        } catch (FeedService.CaptureStartedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Sources ──────────────────────────────────────────────────────────────

    @GetMapping("sources")
    public List<Source> sources() {
        return sourceRepo.list(activeVault.id());
    }

    /** Resolve via the embedder's discover, store the source, insert the current feed contents
     *  as the pending backlog (never auto-accepted). Idempotent per url. */
    @PostMapping("sources")
    public ResponseEntity<?> createSource(@RequestBody CreateSourceRequest req) {
        if (req.url() == null || req.url().isBlank()) return ResponseEntity.badRequest().body("url is required");
        String url = req.url().trim();
        Source existing = sourceRepo.getByUrl(activeVault.id(), url);
        if (existing != null) return ResponseEntity.ok(existing);

        Resolved r = resolve(url);
        if (r == null) return ResponseEntity.unprocessableEntity().body("could not read a feed from that URL");
        String title = (req.title() != null && !req.title().isBlank()) ? req.title().trim() : defaultTitle(url);
        Source s = sourceRepo.create(activeVault.id(), r.kind(), url, r.feedUrl(), title, req.autoAccept());
        pollWorker.ingestCandidates(activeVault.id(), s, r.candidates(), true);
        return ResponseEntity.ok(sourceRepo.get(activeVault.id(), s.id()));
    }

    public record ResolveRequest(String url) {}
    public record PreviewItem(String title, String itemUrl, String publishedAt, String thumbUrl, Integer durationS) {}
    public record ResolveResponse(String kind, String feedUrl, String title, List<PreviewItem> preview) {}

    /** Preview-only: same resolution §3 does for a real subscribe, but nothing is persisted
     *  and no backlog is inserted — "what would I get" before committing. Used by the
     *  extension's Subscribe flow and any future "preview before subscribing" UI. */
    @PostMapping("sources/resolve")
    public ResponseEntity<?> resolveSource(@RequestBody ResolveRequest req) {
        if (req.url() == null || req.url().isBlank()) return ResponseEntity.badRequest().body("url is required");
        String url = req.url().trim();
        Resolved r = resolve(url);
        if (r == null) return ResponseEntity.unprocessableEntity().body("could not read a feed from that URL");
        List<PreviewItem> preview = r.candidates().stream().limit(3)
            .map(c -> new PreviewItem(c.title(), c.itemUrl(), c.publishedAt(), c.thumbUrl(), c.durationS()))
            .toList();
        return ResponseEntity.ok(new ResolveResponse(r.kind(), r.feedUrl(), defaultTitle(url), preview));
    }

    private record Resolved(String kind, String feedUrl, List<TrackAgentClient.Candidate> candidates) {}

    /** youtube/@handle/channel/video → youtube (channel RSS); a youtube …/posts URL →
     *  youtube_posts (Community tab, no RSS); any other page → RSS/Atom autodiscovery (rss);
     *  nothing found there either → page_watch (same-site link diff) — never a hard failure
     *  unless the URL itself is unreachable. See feed/FLOWS_feed.md §3. Shared by createSource
     *  (persists) and resolveSource (preview only). */
    private Resolved resolve(String url) {
        boolean yt = isYoutube(url);
        if (yt && url.toLowerCase().contains("/posts")) {
            DiscoverResult res = trackAgentClient.discover(url, "youtube_posts");
            return res.ok() ? new Resolved("youtube_posts", null, res.candidates()) : null;
        }
        DiscoverResult res = trackAgentClient.discover(url, yt ? "youtube_channel" : "feed");
        if (res.ok()) {
            String feedUrl = res.resolvedFeedUrl() != null ? res.resolvedFeedUrl() : url;
            String kind = (yt || isYoutube(feedUrl)) ? "youtube" : "rss";
            return new Resolved(kind, feedUrl, res.candidates());
        }
        if (yt) return null;   // a broken YouTube URL doesn't fall back to page_watch
        DiscoverResult pw = trackAgentClient.discover(url, "page_watch");
        return pw.ok() ? new Resolved("page_watch", null, pw.candidates()) : null;
    }

    @PatchMapping("sources/{id}")
    public ResponseEntity<?> patchSource(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (sourceRepo.get(activeVault.id(), id) == null) return ResponseEntity.notFound().build();
        Boolean auto = body.get("autoAccept") instanceof Boolean b ? b : null;
        String status = body.get("status") instanceof String s ? s : null;
        if (status != null && !status.equals("active") && !status.equals("paused")) {
            return ResponseEntity.badRequest().body("status must be active|paused");
        }
        String title = body.get("title") instanceof String t && !t.isBlank() ? t.trim() : null;
        return ResponseEntity.ok(sourceRepo.update(activeVault.id(), id,
            auto, body.containsKey("autoAccept") && body.get("autoAccept") == null,
            status, title));
    }

    @DeleteMapping("sources/{id}")
    public ResponseEntity<?> deleteSource(@PathVariable long id) {
        return sourceRepo.delete(activeVault.id(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("sources/{id}/poll")
    public ResponseEntity<?> poll(@PathVariable long id) {
        if (sourceRepo.get(activeVault.id(), id) == null) return ResponseEntity.notFound().build();
        pollWorker.pollNowAsync(activeVault.id(), id);
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
