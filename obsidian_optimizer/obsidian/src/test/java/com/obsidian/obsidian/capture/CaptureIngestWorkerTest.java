package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Drainer logic (claim-and-submit) with mocked repo + IngestClient — no DB, no HTTP.
 * Verifies: only claimed rows submit; a URL resource submits standalone; a 4xx rejection
 * fails the row; a transport failure releases it back to {@code queued}; {@code appReady}
 * gates the drain.
 */
class CaptureIngestWorkerTest {

    private CaptureRepository repo;
    private IngestClient ingest;
    private SettingsRepository settings;
    private NoteIndexRepository noteIndex;
    private FileRepository fileRepo;
    private CaptureIngestWorker worker;

    private static CaptureRepository.Capture url(String id) {
        return new CaptureRepository.Capture(id, "video", "https://youtu.be/" + id, null,
            id, "queued", 0L);
    }

    @BeforeEach
    void setup() {
        repo = mock(CaptureRepository.class);
        ingest = mock(IngestClient.class);
        settings = mock(SettingsRepository.class);
        noteIndex = mock(NoteIndexRepository.class);
        fileRepo = mock(FileRepository.class);
        worker = new CaptureIngestWorker(repo, ingest, settings, noteIndex, fileRepo);
        ReflectionTestUtils.setField(worker, "ingestEnabled", true);
        ReflectionTestUtils.setField(worker, "batchLimit", 25);
        ReflectionTestUtils.setField(worker, "appReady", true);
    }

    @Test
    void submitsClaimedUrlResourceStandalone() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("abc")));
        when(repo.claim("abc")).thenReturn(true);
        when(ingest.submitStandalone("abc", "https://youtu.be/abc", "video"))
            .thenReturn(new IngestClient.Result(true, 200, "job1", "{}"));

        worker.drain();

        verify(ingest).submitStandalone("abc", "https://youtu.be/abc", "video");
        verify(repo, never()).updateStatus(eq("abc"), any());   // stays 'processing' on ok
    }

    @Test
    void skipsRowsLostToConcurrentClaim() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("abc")));
        when(repo.claim("abc")).thenReturn(false);   // another drain won it

        worker.drain();

        verifyNoInteractions(ingest);
    }

    @Test
    void fourxxRejectionFailsTheRow() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("bad")));
        when(repo.claim("bad")).thenReturn(true);
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(new IngestClient.Result(false, 422, null, "unroutable"));

        worker.drain();

        verify(repo).updateStatus("bad", "failed");
    }

    @Test
    void transportFailureReleasesForRetry() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("down")));
        when(repo.claim("down")).thenReturn(true);
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(IngestClient.Result.unreachable());   // status 0

        worker.drain();

        verify(repo).updateStatus("down", "queued");   // back in the queue
    }

    @Test
    void doesNothingBeforeAppReady() {
        ReflectionTestUtils.setField(worker, "appReady", false);
        worker.drain();
        verifyNoInteractions(repo, ingest);
    }
}
