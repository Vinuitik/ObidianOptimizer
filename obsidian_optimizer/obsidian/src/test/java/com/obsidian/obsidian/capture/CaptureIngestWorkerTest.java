package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.common.PipelineFailureRepository;
import com.obsidian.obsidian.common.RabbitQueueConfig;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Drainer logic (claim-and-submit) with mocked repo + IngestClient — no DB, no HTTP, no
 * broker. Verifies: only claimed rows submit; a URL resource submits standalone; a 4xx
 * rejection dead-letters immediately (no ladder); a transport/5xx failure rides the retry
 * ladder (rung 1); a ladder-exhausted redelivery dead-letters; {@code appReady} gates the
 * drain; the dead-letter listener performs its two actions.
 */
class CaptureIngestWorkerTest {

    private CaptureRepository repo;
    private IngestClient ingest;
    private SettingsRepository settings;
    private NoteIndexRepository noteIndex;
    private FileRepository fileRepo;
    private RabbitTemplate rabbitTemplate;
    private PipelineFailureRepository pipelineFailureRepo;
    private CaptureIngestWorker worker;

    private static CaptureRepository.Capture url(String id) {
        return new CaptureRepository.Capture(id, "video", "https://youtu.be/" + id, null,
            id, "queued", null, 0L, null, null, null, 0, null);
    }

    private static CaptureRepository.Capture processing(String id) {
        return new CaptureRepository.Capture(id, "video", "https://youtu.be/" + id, null,
            id, "processing", null, 0L, null, null, null, 0, null);
    }

    private static CaptureRepository.Capture deferred(String id, String bundleRef) {
        return new CaptureRepository.Capture(id, "video", "https://youtu.be/" + id, null,
            id, "deferred", bundleRef, 0L, null, null, null, 0, null);
    }

    private static Message messageFor(String id, Integer attempt) {
        MessageProperties props = new MessageProperties();
        if (attempt != null) props.setHeader("x-attempt-count", attempt);
        String body = "{\"id\":\"" + id + "\"}";
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    @BeforeEach
    void setup() {
        repo = mock(CaptureRepository.class);
        ingest = mock(IngestClient.class);
        settings = mock(SettingsRepository.class);
        noteIndex = mock(NoteIndexRepository.class);
        fileRepo = mock(FileRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        pipelineFailureRepo = mock(PipelineFailureRepository.class);
        worker = new CaptureIngestWorker(repo, ingest, settings, noteIndex, fileRepo,
            rabbitTemplate, pipelineFailureRepo);
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
    void fourxxRejectionDeadLettersImmediately_noLadder() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("bad")));
        when(repo.claim("bad")).thenReturn(true);
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(new IngestClient.Result(false, 422, null, "unroutable"));

        worker.drain();

        // straight to the dead-letter queue — no wait-rung publish, and no direct
        // markFailed from here (that's the dead-letter LISTENER's job, tested below).
        verify(rabbitTemplate).convertAndSend(eq(RabbitQueueConfig.CAPTURE_DEADLETTER_QUEUE), anyString());
        verify(rabbitTemplate, never()).convertAndSend(eq(RabbitQueueConfig.CAPTURE_WAIT_1H_QUEUE), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class));
        verify(repo, never()).markFailed(any(), any());
    }

    @Test
    void transportFailureRidesTheLadder_rung1() {
        when(repo.findQueued(anyInt())).thenReturn(List.of(url("down")));
        when(repo.claim("down")).thenReturn(true);
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(IngestClient.Result.unreachable());   // status 0

        worker.drain();

        verify(rabbitTemplate).convertAndSend(eq(RabbitQueueConfig.CAPTURE_WAIT_1H_QUEUE),
            any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class));
        verify(repo, never()).updateStatus(eq("down"), any());
        verify(repo, never()).markFailed(any(), any());
    }

    @Test
    void ladderRedelivery_advancesToNextRung() {
        when(repo.get("cap-r2")).thenReturn(processing("cap-r2"));
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(IngestClient.Result.unreachable());

        worker.onCaptureMessage(messageFor("cap-r2", 1));   // already failed rung 1 once

        verify(rabbitTemplate).convertAndSend(eq(RabbitQueueConfig.CAPTURE_WAIT_6H_QUEUE),
            any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class));
        verify(repo, never()).claim(any());   // attempt>0 — already claimed on attempt 0
    }

    @Test
    void ladderExhausted_deadLetters() {
        when(repo.get("cap-r3")).thenReturn(processing("cap-r3"));
        when(ingest.submitStandalone(any(), any(), any()))
            .thenReturn(IngestClient.Result.unreachable());

        worker.onCaptureMessage(messageFor("cap-r3", 3));   // last rung just expired, still failing

        verify(rabbitTemplate).convertAndSend(eq(RabbitQueueConfig.CAPTURE_DEADLETTER_QUEUE), anyString());
    }

    @Test
    void listenerIgnoresRedelivery_ifRowNoLongerProcessing() {
        when(repo.get("cap-gone")).thenReturn(null);   // dismissed / dedup-resolved meanwhile

        worker.onCaptureMessage(messageFor("cap-gone", 1));

        verifyNoInteractions(ingest);
    }

    // ── Dead-letter queue listener ───────────────────────────────────────────

    @Test
    void deadLetterListener_marksFailedAndRecordsPipelineFailure() {
        when(repo.get("cap-dl")).thenReturn(url("cap-dl"));
        MessageProperties props = new MessageProperties();
        String body = "{\"id\":\"cap-dl\",\"error\":\"rejected (422): unroutable\"}";

        worker.onCaptureDeadLetter(new Message(body.getBytes(StandardCharsets.UTF_8), props));

        verify(repo).markFailed("cap-dl", "rejected (422): unroutable");
        verify(pipelineFailureRepo).record(eq("capture"), eq("ingest_submit"),
            any(Map.class), isNull(), eq("rejected (422): unroutable"), isNull());
    }

    @Test
    void doesNothingBeforeAppReady() {
        ReflectionTestUtils.setField(worker, "appReady", false);
        worker.drain();
        verifyNoInteractions(repo, ingest);
    }

    // ── synthesis durability: DEFER detection + resume drain ─────────────────

    @Test
    void deferredEmbedderJobParksCaptureWithBundle() {
        when(ingest.listJobs()).thenReturn(List.of(
            new IngestClient.JobView("job1", "cap-1", "DEFERRED",
                "wrapper 503 cooling", "/models/ingest_bundles/job1.json")));

        worker.pollFailures();

        verify(repo).markDeferred("cap-1", "/models/ingest_bundles/job1.json");
        verify(repo, never()).markFailed(any(), any());
    }

    @Test
    void failedEmbedderJobStillMarksFailed() {
        when(ingest.listJobs()).thenReturn(List.of(
            new IngestClient.JobView("job1", "cap-2", "FAILED", "boom", null)));

        worker.pollFailures();

        verify(repo).markFailed("cap-2", "boom");
        verify(repo, never()).markDeferred(any(), any());
    }

    @Test
    void retryResumesDeferredCaptureFromBundle() {
        var cap = deferred("cap-3", "/models/ingest_bundles/b.json");
        when(repo.findDeferred(anyInt())).thenReturn(List.of(cap));
        when(noteIndex.findNotesByCapture("cap-3")).thenReturn(List.of());  // no notes yet
        when(repo.claimDeferred("cap-3")).thenReturn(true);
        when(ingest.resume(any(), any(), any(), any(), any(), any()))
            .thenReturn(new IngestClient.Result(true, 200, "job9", "{}"));

        worker.drainDeferred();

        verify(ingest).resume("/models/ingest_bundles/b.json", "cap-3",
            "https://youtu.be/cap-3", "video", "cap-3", null);
        // outcome reconciled by pollFailures — no direct status write on success
        verify(repo, never()).updateStatus(eq("cap-3"), any());
    }

    @Test
    void retrySettlesCaptureThatAlreadyProducedNotes() {
        // a prior resume succeeded but a status race left it 'deferred' → don't re-run
        var cap = deferred("cap-4", "/models/ingest_bundles/b.json");
        when(repo.findDeferred(anyInt())).thenReturn(List.of(cap));
        when(noteIndex.findNotesByCapture("cap-4"))
            .thenReturn(List.of("/vault/_inbox/note.md"));

        worker.drainDeferred();

        verify(repo).updateStatus("cap-4", "processing");
        verify(repo, never()).claimDeferred(any());
        verifyNoInteractions(ingest);
    }

    @Test
    void retryMissingBundleFailsTheCapture() {
        var cap = deferred("cap-5", "/models/ingest_bundles/gone.json");
        when(repo.findDeferred(anyInt())).thenReturn(List.of(cap));
        when(noteIndex.findNotesByCapture("cap-5")).thenReturn(List.of());
        when(repo.claimDeferred("cap-5")).thenReturn(true);
        when(ingest.resume(any(), any(), any(), any(), any(), any()))
            .thenReturn(new IngestClient.Result(false, 404, null, "bundle not found"));

        worker.drainDeferred();

        verify(repo).updateStatus("cap-5", "failed");
    }

    @Test
    void retryEmbedderDownReDefersForNextTick() {
        var cap = deferred("cap-6", "/models/ingest_bundles/b.json");
        when(repo.findDeferred(anyInt())).thenReturn(List.of(cap));
        when(noteIndex.findNotesByCapture("cap-6")).thenReturn(List.of());
        when(repo.claimDeferred("cap-6")).thenReturn(true);
        when(ingest.resume(any(), any(), any(), any(), any(), any()))
            .thenReturn(IngestClient.Result.unreachable());

        worker.drainDeferred();

        verify(repo).markDeferred("cap-6", "/models/ingest_bundles/b.json");
    }
}
