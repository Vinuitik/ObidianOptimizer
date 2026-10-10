package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.feed.SourceRepository.Source;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackAgentClient;
import com.obsidian.obsidian.tracks.TrackAgentClient.Candidate;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls each active feed source for new items and inserts them as {@code pending}
 * {@code feed_items} (nothing is ingested unless the effective autoAccept is true). Mirrors
 * the other workers: cheap {@code @Scheduled} tick → {@link WorkerLane} → drain.
 * See feed/FLOWS.md.
 */
@Component
public class SourcePollWorker {

    private static final Logger log = LoggerFactory.getLogger(SourcePollWorker.class);

    private final SourceRepository sourceRepo;
    private final FeedRepository feedRepo;
    private final FeedService feedService;
    private final SettingsRepository settingsRepo;
    private final TrackAgentClient trackAgentClient;
    private final WorkerLane lane = new WorkerLane("feed-poll");

    public SourcePollWorker(SourceRepository sourceRepo, FeedRepository feedRepo, FeedService feedService,
                            SettingsRepository settingsRepo, TrackAgentClient trackAgentClient) {
        this.sourceRepo = sourceRepo;
        this.feedRepo = feedRepo;
        this.feedService = feedService;
        this.settingsRepo = settingsRepo;
        this.trackAgentClient = trackAgentClient;
    }

    @Scheduled(fixedDelayString = "${subscriptions.poll.tick-ms:300000}",
               initialDelayString = "${subscriptions.poll.initial-delay-ms:30000}")
    public void tick() {
        lane.trigger(this::drain);
    }

    void drain() {
        long intervalMs = settingsRepo.getSubscriptionPollIntervalMs();
        for (Source s : sourceRepo.listActive()) {
            boolean due = s.lastCheckedAt() == null
                || Instant.now().isAfter(s.lastCheckedAt().plusMillis(intervalMs));
            if (!due) continue;
            try {
                pollSource(s);
            } catch (RuntimeException e) {
                log.warn("[SourcePollWorker] source {} ({}) failed: {}", s.id(), s.url(), e.toString());
            }
        }
    }

    /** User-triggered "check now" — async, bypasses the interval gate. */
    public void pollNowAsync(long sourceId) {
        CompletableFuture.runAsync(() -> {
            Source s = sourceRepo.get(sourceId);
            if (s != null) {
                try { pollSource(s); }
                catch (RuntimeException e) { log.warn("[SourcePollWorker] poll-now {} failed: {}", sourceId, e.toString()); }
            }
        });
    }

    /** discover → insert. A source never checked before is the first-poll BACKLOG: always pending. */
    void pollSource(Source s) {
        String target = s.feedUrl() != null ? s.feedUrl() : s.url();
        DiscoverResult res = trackAgentClient.discover(target, discoverType(s));
        if (!res.ok()) return;   // last_checked_at untouched, retried next tick
        ingestCandidates(s, res.candidates(), s.lastCheckedAt() == null);
    }

    /** Insert candidates as pending (dedup by normalized item_url), auto-accept NEW ones only when
     *  not backlog and effective autoAccept (source value, else global) is true; advance the cursor
     *  (also for an empty feed). Returns the number of new items. */
    int ingestCandidates(Source s, List<Candidate> candidates, boolean backlog) {
        boolean auto = !backlog && (s.autoAccept() != null ? s.autoAccept() : settingsRepo.isFeedAutoAccept());
        int inserted = 0;
        for (Candidate c : candidates) {
            String url = normalizeUrl(c.itemUrl());
            if (url == null) continue;
            String title = (c.title() == null || c.title().isBlank()) ? url : c.title();
            Long id = feedRepo.insertIfNew(s.id(), url, title, c.thumbUrl(), c.durationS(),
                parseDate(c.publishedAt()), c.body());
            if (id == null) continue;
            inserted++;
            if (auto) feedService.accept(id);
        }
        sourceRepo.markChecked(s.id(), Instant.now());
        if (inserted > 0) {
            log.info("[SourcePollWorker] source {} ({}): {} new item(s){}", s.id(), s.url(), inserted,
                backlog ? " (backlog)" : auto ? " (auto-accepted)" : "");
        }
        return inserted;
    }

    /** Embedder discover type: page_watch/youtube_posts are self-contained kinds; playlists
     *  keep the yt-dlp path; everything else (incl. the resolved YouTube channel RSS) goes
     *  through the feed path. */
    static String discoverType(Source s) {
        if ("page_watch".equals(s.kind())) return "page_watch";
        if ("youtube_posts".equals(s.kind())) return "youtube_posts";
        boolean ytRss = s.feedUrl() != null && s.feedUrl().contains("/feeds/videos.xml");
        return "youtube".equals(s.kind()) && !ytRss ? "youtube_channel" : "feed";
    }

    // ── URL normalization ────────────────────────────────────────────────────

    private static final Pattern YT_ID = Pattern.compile("^/(?:shorts|embed|live|v)/([\\w-]{6,})");

    /** YouTube variants (youtu.be/ID, /shorts/ID, /embed/ID, m./music. hosts) → https://www.youtube.com/watch?v=ID;
     *  other URLs: lowercase host, drop fragment and utm_* params. Null for blank/unparseable. */
    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        URI u;
        try { u = URI.create(raw.trim()); } catch (IllegalArgumentException e) { return raw.trim(); }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String path = u.getPath() == null ? "" : u.getPath();
        String vid = null;
        if (host.equals("youtu.be")) {
            if (path.length() > 1) vid = path.substring(1).split("/")[0];
        } else if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            if (path.equals("/watch")) vid = queryParam(u.getRawQuery(), "v");
            else {
                Matcher m = YT_ID.matcher(path);
                if (m.find()) vid = m.group(1);
            }
        }
        if (vid != null && !vid.isBlank()) return "https://www.youtube.com/watch?v=" + vid;
        if (u.getScheme() == null || u.getHost() == null) return raw.trim();
        List<String> kept = new ArrayList<>();
        if (u.getRawQuery() != null) {
            for (String p : u.getRawQuery().split("&")) {
                if (!p.isEmpty() && !p.toLowerCase().startsWith("utm_")) kept.add(p);
            }
        }
        StringBuilder sb = new StringBuilder(u.getScheme().toLowerCase()).append("://").append(host);
        if (u.getPort() != -1) sb.append(':').append(u.getPort());
        sb.append(u.getRawPath() == null ? "" : u.getRawPath());
        if (!kept.isEmpty()) sb.append('?').append(String.join("&", kept));
        return sb.toString();
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String p : rawQuery.split("&")) {
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return null;
    }

    /** ISO instant/offset, RFC-1123 (RSS), or plain date; null if unparseable. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try { return OffsetDateTime.parse(v).toInstant(); } catch (Exception ignored) {}
        try { return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ignored) {}
        try { return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (Exception ignored) {}
        return null;
    }
}
