package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.feed.SourceRepository.Source;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackAgentClient;
import com.obsidian.obsidian.tracks.TrackAgentClient.Candidate;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Poll/drain logic with mocked collaborators — no DB, no HTTP. */
class SourcePollWorkerTest {

    SourceRepository sourceRepo;
    FeedRepository feedRepo;
    FeedService feedService;
    SettingsRepository settings;
    TrackAgentClient client;
    SourcePollWorker worker;

    static Source src(long id, Boolean auto, Instant lastChecked) {
        return new Source(id, "rss", "https://blog.example/", "https://blog.example/feed.xml", "Blog",
            auto, null, "active", lastChecked, Instant.now(), 0);
    }

    static Candidate cand(String url) { return new Candidate(url, "T " + url, "2026-08-01T00:00:00Z", "https://i/t.jpg", 60); }

    @BeforeEach
    void setup() {
        sourceRepo = mock(SourceRepository.class);
        feedRepo = mock(FeedRepository.class);
        feedService = mock(FeedService.class);
        settings = mock(SettingsRepository.class);
        client = mock(TrackAgentClient.class);
        worker = new SourcePollWorker(sourceRepo, feedRepo, feedService, settings, client);
        when(settings.getSubscriptionPollIntervalMs()).thenReturn(21_600_000L);
        when(feedRepo.insertIfNew(anyLong(), any(), any(), any(), any(), any())).thenReturn(100L, 101L, 102L);
    }

    private void discovers(Candidate... cs) {
        when(client.discover(any(), any())).thenReturn(new DiscoverResult(true, List.of(cs), null));
    }

    @Test
    void autoAcceptFalse_insertsPending_neverAccepts() {
        Source s = src(1, false, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        discovers(cand("https://blog.example/a"), cand("https://blog.example/b"));

        worker.drain();

        verify(feedRepo, times(2)).insertIfNew(eq(1L), any(), any(), eq("https://i/t.jpg"), eq(60), any());
        verify(feedService, never()).accept(anyLong());
        verify(sourceRepo).markChecked(eq(1L), any());
    }

    @Test
    void autoAcceptTrue_acceptsNewItems() {
        Source s = src(1, true, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        discovers(cand("https://blog.example/a"), cand("https://blog.example/b"));

        worker.drain();

        verify(feedService).accept(100L);
        verify(feedService).accept(101L);
    }

    @Test
    void autoAcceptNull_inheritsGlobal() {
        Source s = src(1, null, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        discovers(cand("https://blog.example/a"));

        when(settings.isFeedAutoAccept()).thenReturn(false);
        worker.drain();
        verify(feedService, never()).accept(anyLong());

        when(settings.isFeedAutoAccept()).thenReturn(true);
        worker.drain();
        verify(feedService).accept(anyLong());
    }

    @Test
    void sourceFalseOverridesGlobalTrue() {
        Source s = src(1, false, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        when(settings.isFeedAutoAccept()).thenReturn(true);
        discovers(cand("https://blog.example/a"));

        worker.drain();

        verify(feedService, never()).accept(anyLong());
    }

    @Test
    void firstPoll_backlog_neverAutoAccepted_evenIfSourceAuto() {
        Source s = src(1, true, null);
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        when(settings.isFeedAutoAccept()).thenReturn(true);
        discovers(cand("https://blog.example/a"), cand("https://blog.example/b"));

        worker.drain();

        verify(feedRepo, times(2)).insertIfNew(anyLong(), any(), any(), any(), any(), any());
        verify(feedService, never()).accept(anyLong());
        verify(sourceRepo).markChecked(eq(1L), any());
    }

    @Test
    void duplicate_insertReturnsNull_notAccepted() {
        Source s = src(1, true, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        when(feedRepo.insertIfNew(anyLong(), any(), any(), any(), any(), any())).thenReturn(null);
        discovers(cand("https://blog.example/a"));

        worker.drain();

        verify(feedService, never()).accept(anyLong());
    }

    @Test
    void emptyFeed_isOk_cursorAdvances() {
        Source s = src(1, true, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        discovers();

        worker.drain();

        verify(feedRepo, never()).insertIfNew(anyLong(), any(), any(), any(), any(), any());
        verify(sourceRepo).markChecked(eq(1L), any());
    }

    @Test
    void discoverFailure_leavesCursorUntouched() {
        Source s = src(1, true, null);
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        when(client.discover(any(), any())).thenReturn(new DiscoverResult(false, List.of(), null));

        worker.drain();

        verify(sourceRepo, never()).markChecked(anyLong(), any());
    }

    @Test
    void notDue_skipped() {
        Source s = src(1, true, Instant.now().minusSeconds(60));
        when(sourceRepo.listActive()).thenReturn(List.of(s));

        worker.drain();

        verifyNoInteractions(client);
    }

    @Test
    void itemUrlsAreNormalizedBeforeInsert() {
        Source s = src(1, false, Instant.now().minusSeconds(7 * 3600));
        when(sourceRepo.listActive()).thenReturn(List.of(s));
        discovers(cand("https://youtu.be/abcdefghijk?utm_source=x"), cand("https://blog.example/p?utm_medium=y&id=3"));

        worker.drain();

        verify(feedRepo).insertIfNew(eq(1L), eq("https://www.youtube.com/watch?v=abcdefghijk"), any(), any(), any(), any());
        verify(feedRepo).insertIfNew(eq(1L), eq("https://blog.example/p?id=3"), any(), any(), any(), any());
    }

    @Test
    void normalizeUrl_youtubeVariantsAndUtm() {
        String want = "https://www.youtube.com/watch?v=abcdefghijk";
        assertThat(SourcePollWorker.normalizeUrl("https://youtu.be/abcdefghijk")).isEqualTo(want);
        assertThat(SourcePollWorker.normalizeUrl("https://m.youtube.com/watch?v=abcdefghijk&t=5")).isEqualTo(want);
        assertThat(SourcePollWorker.normalizeUrl("https://www.youtube.com/shorts/abcdefghijk")).isEqualTo(want);
        assertThat(SourcePollWorker.normalizeUrl("https://www.youtube.com/embed/abcdefghijk")).isEqualTo(want);
        assertThat(SourcePollWorker.normalizeUrl("HTTPS://Blog.Example/p?utm_source=a&x=1#frag")).isEqualTo("https://blog.example/p?x=1");
        assertThat(SourcePollWorker.normalizeUrl("  ")).isNull();
        assertThat(SourcePollWorker.normalizeUrl(null)).isNull();
    }

    @Test
    void parseDate_isoRfc1123AndGarbage() {
        assertThat(SourcePollWorker.parseDate("2026-08-01T00:00:00+00:00")).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(SourcePollWorker.parseDate("Sat, 01 Aug 2026 10:00:00 GMT")).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
        assertThat(SourcePollWorker.parseDate("nonsense")).isNull();
    }
}
