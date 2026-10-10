package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.feed.SourceRepository.Source;
import com.obsidian.obsidian.tracks.TrackAgentClient;
import com.obsidian.obsidian.tracks.TrackAgentClient.Candidate;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P3: resolve() 3-way fallback (youtube / youtube_posts / rss / page_watch) and the
 *  /feed/sources/resolve preview endpoint — mocked collaborators, no DB/HTTP. */
class FeedControllerTest {

    SourceRepository sourceRepo;
    FeedRepository feedRepo;
    FeedService feedService;
    SourcePollWorker pollWorker;
    TrackAgentClient client;
    FeedController controller;

    static Candidate cand(String url, String title) {
        return new Candidate(url, title, "2026-08-01T00:00:00Z", "https://i/t.jpg", 60, null);
    }

    static Source src(long id, String kind, String url) {
        return new Source(id, kind, url, null, "T", null, null, "active", Instant.now(), Instant.now(), 0);
    }

    @BeforeEach
    void setup() {
        sourceRepo = mock(SourceRepository.class);
        feedRepo = mock(FeedRepository.class);
        feedService = mock(FeedService.class);
        pollWorker = mock(SourcePollWorker.class);
        client = mock(TrackAgentClient.class);
        controller = new FeedController(feedRepo, sourceRepo, feedService, pollWorker, client);
    }

    // ── resolveSource ────────────────────────────────────────────────────────

    @Test
    void resolveSource_youtubeChannel_picksYoutubeKind() {
        String url = "https://www.youtube.com/@veritasium";
        when(client.discover(url, "youtube_channel")).thenReturn(new DiscoverResult(true,
            List.of(cand("https://youtu.be/a", "A"), cand("https://youtu.be/b", "B"),
                   cand("https://youtu.be/c", "C"), cand("https://youtu.be/d", "D")),
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCx"));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (FeedController.ResolveResponse) resp.getBody();
        assertThat(body.kind()).isEqualTo("youtube");
        assertThat(body.feedUrl()).isEqualTo("https://www.youtube.com/feeds/videos.xml?channel_id=UCx");
        assertThat(body.title()).isEqualTo("@veritasium");
        assertThat(body.preview()).hasSize(3);   // capped, even though discover returned 4
        verify(client, never()).discover(any(), eq("page_watch"));
        verify(client, never()).discover(any(), eq("youtube_posts"));
    }

    @Test
    void resolveSource_youtubePosts_skipsChannelAndFeedProbes() {
        String url = "https://www.youtube.com/@veritasium/posts";
        when(client.discover(url, "youtube_posts")).thenReturn(new DiscoverResult(true,
            List.of(cand("https://www.youtube.com/post/Ug1", "Post one")), null));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        var body = (FeedController.ResolveResponse) resp.getBody();
        assertThat(body.kind()).isEqualTo("youtube_posts");
        assertThat(body.feedUrl()).isNull();
        assertThat(body.preview()).hasSize(1);
        verify(client, never()).discover(any(), eq("youtube_channel"));
        verify(client, never()).discover(any(), eq("feed"));
    }

    @Test
    void resolveSource_plainPage_withFeed_picksRss() {
        String url = "https://lilianweng.github.io/";
        when(client.discover(url, "feed")).thenReturn(new DiscoverResult(true,
            List.of(cand("https://lilianweng.github.io/p1", "P1")), "https://lilianweng.github.io/index.xml"));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        var body = (FeedController.ResolveResponse) resp.getBody();
        assertThat(body.kind()).isEqualTo("rss");
        assertThat(body.feedUrl()).isEqualTo("https://lilianweng.github.io/index.xml");
        verify(client, never()).discover(any(), eq("page_watch"));
    }

    @Test
    void resolveSource_noFeedFound_fallsBackToPageWatch() {
        String url = "https://arxiv.org/list/cs.LG/recent";
        when(client.discover(url, "feed")).thenReturn(new DiscoverResult(false, List.of(), null));
        when(client.discover(url, "page_watch")).thenReturn(new DiscoverResult(true,
            List.of(cand("https://arxiv.org/list/cs.LG/2026-10", "Listing")), null));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (FeedController.ResolveResponse) resp.getBody();
        assertThat(body.kind()).isEqualTo("page_watch");
        assertThat(body.feedUrl()).isNull();
        assertThat(body.preview()).hasSize(1);
    }

    @Test
    void resolveSource_brokenYoutubeUrl_doesNotFallBackToPageWatch() {
        String url = "https://www.youtube.com/watch?v=doesnotexist";
        when(client.discover(url, "youtube_channel")).thenReturn(new DiscoverResult(false, List.of(), null));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(client, never()).discover(any(), eq("page_watch"));
    }

    @Test
    void resolveSource_totalFailure_422() {
        String url = "https://unreachable.example/";
        when(client.discover(url, "feed")).thenReturn(new DiscoverResult(false, List.of(), null));
        when(client.discover(url, "page_watch")).thenReturn(new DiscoverResult(false, List.of(), null));

        var resp = controller.resolveSource(new FeedController.ResolveRequest(url));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void resolveSource_blankUrl_badRequest() {
        var resp = controller.resolveSource(new FeedController.ResolveRequest("  "));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(client);
    }

    @Test
    void resolveSource_doesNotPersistOrPoll() {
        String url = "https://lilianweng.github.io/";
        when(client.discover(url, "feed")).thenReturn(new DiscoverResult(true,
            List.of(cand("https://lilianweng.github.io/p1", "P1")), null));

        controller.resolveSource(new FeedController.ResolveRequest(url));

        verifyNoInteractions(sourceRepo);
        verifyNoInteractions(pollWorker);
    }

    // ── createSource (page_watch / youtube_posts kinds persist correctly) ─────

    @Test
    void createSource_pageWatchFallback_persistsWithNullFeedUrlAndPolls() {
        String url = "https://arxiv.org/list/cs.LG/recent";
        when(sourceRepo.getByUrl(url)).thenReturn(null);
        when(client.discover(url, "feed")).thenReturn(new DiscoverResult(false, List.of(), null));
        List<Candidate> candidates = List.of(cand("https://arxiv.org/list/cs.LG/2026-10", "Listing"));
        when(client.discover(url, "page_watch")).thenReturn(new DiscoverResult(true, candidates, null));
        Source created = src(1, "page_watch", url);
        when(sourceRepo.create("page_watch", url, null, "arxiv.org", null, null)).thenReturn(created);
        when(sourceRepo.get(1)).thenReturn(created);

        ResponseEntity<?> resp = controller.createSource(new FeedController.CreateSourceRequest(url, null, null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(pollWorker).ingestCandidates(created, candidates, true);
    }

    @Test
    void createSource_existingUrl_shortCircuitsBeforeDiscover() {
        String url = "https://lilianweng.github.io/";
        Source existing = src(5, "rss", url);
        when(sourceRepo.getByUrl(url)).thenReturn(existing);

        ResponseEntity<?> resp = controller.createSource(new FeedController.CreateSourceRequest(url, null, null, null));

        assertThat(resp.getBody()).isEqualTo(existing);
        verifyNoInteractions(client);
        verifyNoInteractions(pollWorker);
    }
}
