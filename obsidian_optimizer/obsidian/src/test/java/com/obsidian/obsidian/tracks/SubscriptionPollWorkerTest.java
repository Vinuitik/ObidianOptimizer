package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackAgentClient.Candidate;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Poll/drain logic with mocked repos + TrackAgentClient — no DB, no HTTP. Mirrors
 * CaptureIngestWorkerTest's convention (mocked collaborators, no real scheduler).
 */
class SubscriptionPollWorkerTest {

    private TrackRepository trackRepo;
    private CaptureRepository captureRepo;
    private CaptureIngestWorker captureIngestWorker;
    private SettingsRepository settingsRepo;
    private TrackAgentClient trackAgentClient;
    private SubscriptionPollWorker worker;

    private static Track subscription(long id, String sourceUrl, Instant lastCheckedAt) {
        return new Track(id, "Channel", "subscription", "active", "manual", null, null, true,
            Instant.now(), sourceUrl, "youtube_channel", lastCheckedAt);
    }

    @BeforeEach
    void setup() {
        trackRepo = mock(TrackRepository.class);
        captureRepo = mock(CaptureRepository.class);
        captureIngestWorker = mock(CaptureIngestWorker.class);
        settingsRepo = mock(SettingsRepository.class);
        trackAgentClient = mock(TrackAgentClient.class);
        worker = new SubscriptionPollWorker(trackRepo, captureRepo, captureIngestWorker, settingsRepo, trackAgentClient);
        when(settingsRepo.getSubscriptionPollIntervalMs()).thenReturn(3_600_000L);
    }

    @Test
    void drain_newCandidates_enqueuesAndNudgesAndMarksChecked() {
        Track t = subscription(1L, "https://youtube.com/@ch", null);
        when(trackRepo.listActive()).thenReturn(List.of(t));
        when(trackAgentClient.discover("https://youtube.com/@ch", "youtube_channel")).thenReturn(
            new DiscoverResult(true, List.of(
                new Candidate("https://youtu.be/a", "Video A", "2026-08-01"),
                new Candidate("https://youtu.be/b", "Video B", "2026-08-02")), null));
        when(captureRepo.existsForSource(any())).thenReturn(false);

        worker.drain();

        verify(captureRepo).enqueue(any(), eq("video"), eq("https://youtu.be/a"), eq(null), eq("Video A"));
        verify(captureRepo).enqueue(any(), eq("video"), eq("https://youtu.be/b"), eq(null), eq("Video B"));
        verify(captureRepo, times(2)).setTrackId(any(), eq(1L));
        verify(captureIngestWorker, times(1)).nudge();
        verify(trackRepo).markChecked(eq(1L), any());
    }

    @Test
    void drain_alreadySeenCandidate_skipsOnlyThatOne() {
        Track t = subscription(1L, "https://youtube.com/@ch", null);
        when(trackRepo.listActive()).thenReturn(List.of(t));
        when(trackAgentClient.discover(any(), any())).thenReturn(
            new DiscoverResult(true, List.of(
                new Candidate("https://youtu.be/a", "Video A", null),
                new Candidate("https://youtu.be/b", "Video B", null)), null));
        when(captureRepo.existsForSource("https://youtu.be/a")).thenReturn(true);
        when(captureRepo.existsForSource("https://youtu.be/b")).thenReturn(false);

        worker.drain();

        verify(captureRepo, never()).enqueue(any(), any(), eq("https://youtu.be/a"), any(), any());
        verify(captureRepo).enqueue(any(), any(), eq("https://youtu.be/b"), any(), any());
        verify(captureIngestWorker, times(1)).nudge();
    }

    @Test
    void drain_discoverFails_noEnqueueNoNudgeNoMarkChecked() {
        Track t = subscription(1L, "https://youtube.com/@ch", null);
        when(trackRepo.listActive()).thenReturn(List.of(t));
        when(trackAgentClient.discover(any(), any())).thenReturn(new DiscoverResult(false, List.of(), null));

        worker.drain();

        verify(captureRepo, never()).enqueue(any(), any(), any(), any(), any());
        verify(captureIngestWorker, never()).nudge();
        verify(trackRepo, never()).markChecked(anyLong(), any());
    }

    @Test
    void drain_recentlyChecked_notPolledThisTick() {
        Track t = subscription(1L, "https://youtube.com/@ch", Instant.now());
        when(trackRepo.listActive()).thenReturn(List.of(t));

        worker.drain();

        verifyNoInteractions(trackAgentClient);
        verify(trackRepo, never()).markChecked(anyLong(), any());
    }

    @Test
    void drain_skipsNonSubscriptionAndNullSourceUrl() {
        Track custom = new Track(2L, "Rust Book", "custom", "active", "manual", null, null, true,
            Instant.now(), null, null, null);
        Track subNoUrl = new Track(3L, "Channel", "subscription", "active", "manual", null, null, true,
            Instant.now(), null, "youtube_channel", null);
        when(trackRepo.listActive()).thenReturn(List.of(custom, subNoUrl));

        worker.drain();

        verifyNoInteractions(trackAgentClient);
    }

    @Test
    void pollNow_nonSubscriptionTrack_returnsFalse_discoverNeverCalled() {
        Track custom = new Track(4L, "Rust Book", "custom", "active", "manual", null, null, true,
            Instant.now(), null, null, null);
        when(trackRepo.get(4L)).thenReturn(custom);

        boolean result = worker.pollNow(4L);

        assertFalse(result);
        verifyNoInteractions(trackAgentClient);
    }

    @Test
    void pollNow_bypassesIntervalGate_pollsEvenIfRecentlyChecked() {
        Track t = subscription(5L, "https://youtube.com/@ch", Instant.now());
        when(trackRepo.get(5L)).thenReturn(t);
        when(trackAgentClient.discover(any(), any())).thenReturn(new DiscoverResult(true, List.of(), null));

        boolean result = worker.pollNow(5L);

        assertTrue(result);
        verify(trackAgentClient).discover("https://youtube.com/@ch", "youtube_channel");
        verify(trackRepo).markChecked(eq(5L), any());
    }
}
