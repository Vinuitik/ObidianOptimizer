package com.obsidian.obsidian.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.common.PipelineFailureRepository;
import com.obsidian.obsidian.common.RabbitQueueConfig;
import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Continuously drains the durable capture queue into the ingest pipeline. The user
 * drops resources (shared links, pasted text) from time to time via
 * {@link CaptureController}; each lands as a {@code queued} row. This worker keeps
 * pulling {@code queued} resources and submitting them through the one
 * {@link IngestClient} gate until the queue is empty — then idles until the next
 * arrival (a {@link #nudge()} from the controller, or the scheduled tick).
 *
 * <p>Why a durable queue and not fire-on-capture: the embedder's own job queue is
 * in-memory (a restart loses anything not yet drained), and the embedder can be down
 * when a resource arrives. Persisting to the {@code capture} table and draining from
 * it means nothing is dropped — a resource captured while the embedder is offline is
 * simply submitted once it returns. Mirrors {@code ImageProcessingWorker}: cheap
 * {@code @Scheduled} tick → {@link WorkerLane} → claim-and-submit drain.
 *
 * <p>Pacing is intentionally light: submitting is a fast POST (the embedder does the
 * minutes-long work off its own worker thread), so a claimed resource flips to
 * {@code processing} in milliseconds; a transient submit failure releases it back to
 * {@code queued} for the next tick.
 */
@Component
public class CaptureIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(CaptureIngestWorker.class);

    private final CaptureRepository captureRepo;
    private final IngestClient ingestClient;
    private final SettingsRepository settingsRepo;
    private final NoteIndexRepository noteIndex;
    private final FileRepository fileRepo;
    private final RabbitTemplate rabbitTemplate;
    private final PipelineFailureRepository pipelineFailureRepo;
    private final WorkerLane lane = new WorkerLane("capture-ingest");
    private final ObjectMapper mapper = new ObjectMapper();

    // Captures currently riding the retry ladder (claimed, submitted at least once,
    // waiting in a capture.wait.* rung) — in-memory, lost on restart like
    // sessionRetryAttempts used to be. Purpose: cleanupOrphanSources() must not treat a
    // capture that's legitimately backing off for up to 24h as an abandoned 'processing'
    // row and trash its source (see FLOWS.md — this is the one deliberate touch to that
    // otherwise-untouched sweep). A restart during a rung wait re-exposes the row to the
    // sweep for at most one cleanup cycle — an accepted, rare edge case, same tradeoff
    // class as the old session-retry cap.
    private final Set<String> ridingLadder = ConcurrentHashMap.newKeySet();

    // Orphan-source cleanup: a capture with no notes left, older than this, and not actively
    // ingesting → its kept source file is trashed (the user's "no children → delete the
    // source" rule). Generous age gate so a long in-flight ingest is never mistaken for orphaned.
    @Value("${ingest.cleanup.min-age-ms:1800000}")   // 30 min
    private long cleanupMinAgeMs;

    // Shared master switch with ResourceScanService: off → the whole ingest pipeline
    // is disabled, so leave queued resources untouched (they drain when re-enabled).
    @Value("${ingest.enabled:true}")
    private boolean ingestEnabled;

    // Resources submitted per drain. Submitting is cheap; this just bounds one tick.
    @Value("${ingest.capture.batch-limit:25}")
    private int batchLimit;

    // The embedder publishes synthesized notes BACK to this backend; during boot Tomcat
    // isn't bound yet, so hold submission until the app is ready (same reasoning as
    // ResourceScanService). The scheduled tick drains the backlog once ready.
    private volatile boolean appReady = false;

    public CaptureIngestWorker(CaptureRepository captureRepo, IngestClient ingestClient,
                               SettingsRepository settingsRepo, NoteIndexRepository noteIndex,
                               FileRepository fileRepo, RabbitTemplate rabbitTemplate,
                               PipelineFailureRepository pipelineFailureRepo) {
        this.captureRepo = captureRepo;
        this.ingestClient = ingestClient;
        this.settingsRepo = settingsRepo;
        this.noteIndex = noteIndex;
        this.fileRepo = fileRepo;
        this.rabbitTemplate = rabbitTemplate;
        this.pipelineFailureRepo = pipelineFailureRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        appReady = true;
        nudge();   // drain anything captured (or left over) before the first tick
    }

    @PreDestroy
    void shutdown() {
        lane.shutdown();
    }

    /** Scheduled heartbeat — now the SAFETY NET (default 5min, was 15s): the outbox+
     *  RabbitMQ fast path from CaptureController's enqueue chokepoint delivers within
     *  seconds instead of waiting for this tick (QUEUE_UNIFICATION_PLAN.md Phase 4). This
     *  still exists to catch whatever that path missed — a crash between the DB commit
     *  and the outbox publish, or a genuinely lost message. */
    @Scheduled(fixedDelayString = "${ingest.capture.delay-ms:300000}",
               initialDelayString = "${ingest.capture.initial-delay-ms:20000}")
    public void tick() {
        if (!ingestEnabled) return;
        lane.trigger(this::drain);
    }

    /** Kick the drain immediately (called by the controller right after enqueue) so a
     *  freshly captured resource ingests without waiting for the next tick. No-op if a
     *  drain is already in flight — the running drain will pick the new row up. */
    public void nudge() {
        if (!ingestEnabled) return;
        lane.trigger(this::drain);
    }

    /** Failure visibility (AGENT_ESCALATION prerequisite): a job that FAILS after a successful
     *  submit leaves its capture stranded at 'processing' forever — a silent drop. Poll the
     *  embedder job registry and flip those captures to 'failed' so they surface (and become
     *  the hook the escalation agent fires off). Best-effort; the embedder job carries capture_id.*/
    @Scheduled(fixedDelayString = "${ingest.capture.poll-ms:20000}",
               initialDelayString = "${ingest.capture.initial-delay-ms:20000}")
    public void pollFailures() {
        if (!ingestEnabled || !appReady) return;
        for (IngestClient.JobView j : ingestClient.listJobs()) {
            if (j.captureId() == null) continue;
            if ("DEFERRED".equals(j.status())) {
                // Synthesis blocked on LLM providers — not a failure. Park the capture as
                // 'deferred' with its bundle so retryDeferred resumes it (no re-extract).
                if (captureRepo.markDeferred(j.captureId(), j.bundlePath())) {
                    log.info("[CaptureIngestWorker] capture {} DEFERRED (providers cooling), "
                        + "will retry synthesis from bundle", j.captureId());
                }
            } else if ("FAILED".equals(j.status()) && captureRepo.markFailed(j.captureId(), j.error())) {
                log.warn("[CaptureIngestWorker] capture {} ingest FAILED: {}",
                    j.captureId(), j.error());
            }
        }
    }

    /** Retry DEFERRED synthesis when LLM providers may have recovered. Slow cadence (we're
     *  waiting on cooldowns, not hammering): claim a deferred capture, resume from its saved
     *  bundle (no re-extract). Idempotent + restart-safe — the deferred state is a DB row and
     *  the bundle a file on the embedder volume, so neither an app nor an embedder restart
     *  loses the work. Guard against duplicate notes: if the capture already produced notes
     *  (a prior resume succeeded but a status race re-deferred it), settle it instead. */
    @Scheduled(fixedDelayString = "${ingest.capture.retry-deferred-ms:180000}",
               initialDelayString = "${ingest.capture.initial-delay-ms:20000}")
    public void retryDeferred() {
        if (!ingestEnabled || !appReady) return;
        lane.trigger(this::drainDeferred);
    }

    void drainDeferred() {
        for (CaptureRepository.Capture c : captureRepo.findDeferred(batchLimit)) {
            // Idempotency guard: synthesis already published notes for this capture → don't
            // re-run it (would duplicate). Settle it back to processing (awaiting triage).
            if (!noteIndex.findNotesByCapture(c.id()).isEmpty()) {
                captureRepo.updateStatus(c.id(), "processing");
                continue;
            }
            if (c.bundleRef() == null || c.bundleRef().isBlank()) continue;  // nothing to resume
            if (!captureRepo.claimDeferred(c.id())) continue;                // a concurrent retry won it
            IngestClient.Result res = ingestClient.resume(
                c.bundleRef(), c.id(), c.sourceRef(), c.sourceType(), c.title(), null);
            if (res.ok()) {
                log.info("[CaptureIngestWorker] resumed synthesis for capture {}", c.id());
                // outcome (DONE / DEFERRED again / FAILED) is reconciled by pollFailures
            } else if (res.status() == 404) {
                captureRepo.updateStatus(c.id(), "failed");   // bundle gone — can't resume
                log.warn("[CaptureIngestWorker] capture {} bundle missing — marked failed", c.id());
            } else {
                captureRepo.markDeferred(c.id(), c.bundleRef());  // embedder down/5xx — retry next tick
            }
        }
    }

    /** Orphan-source cleanup (the user's "once a source has no children we delete it" rule).
     *  A capture whose proposed notes were ALL deleted leaves its kept original (uploaded PDF,
     *  saved media) sitting in the vault. This sweep trashes it. Guards against nuking a source
     *  mid-ingest: only captures older than {@code cleanupMinAgeMs} AND with no active embedder
     *  job are considered. (Per-note deletes already clean up immediately via InboxController
     *  Stage 4; this is the safety net for that + failed captures + restart-orphaned sources.) */
    @Scheduled(fixedDelayString = "${ingest.cleanup.delay-ms:120000}",
               initialDelayString = "${ingest.cleanup.initial-delay-ms:45000}")
    public void cleanupOrphanSources() {
        if (!ingestEnabled || !appReady) return;
        Set<String> active = new HashSet<>();
        for (IngestClient.JobView j : ingestClient.listJobs()) {
            if (("QUEUED".equals(j.status()) || "RUNNING".equals(j.status())) && j.captureId() != null)
                active.add(j.captureId());
        }
        // 'failed' captures are deliberately NOT swept here — they only leave 'failed' by
        // succeeding (a manual retry re-entering the flow) or an explicit user dismiss.
        long cutoff = System.currentTimeMillis() - cleanupMinAgeMs;
        for (CaptureRepository.Capture c : captureRepo.findStaleActive(cutoff)) {
            sweepOrphan(c, active);
        }
    }

    private void sweepOrphan(CaptureRepository.Capture c, Set<String> active) {
        if (active.contains(c.id())) return;                          // still ingesting
        if (ridingLadder.contains(c.id())) return;                    // backing off, not abandoned
        if (!noteIndex.findNotesByCapture(c.id()).isEmpty()) return;  // still has children
        String file = localVaultFile(c);
        // Don't trash a file a DUPLICATE capture (same upload twice) still shares — its
        // sibling may still have notes pointing at it. Only the last reference trashes it.
        boolean trash = file != null && captureRepo.countLiveReferencesToFile(file, c.id()) == 0;
        if (trash) trashVaultFile(file);
        captureRepo.updateStatus(c.id(), "discarded");
        log.info("[cleanup] capture {} has no notes → discarded{}",
            c.id(), trash ? " + trashed source " + file
                          : (file != null ? " (source " + file + " kept — shared)" : ""));
    }

    /** The capture's kept original as a vault-local file, or null (external URL / nothing). */
    private String localVaultFile(CaptureRepository.Capture c) {
        for (String p : new String[]{ c.sourcePath(), c.sourceRef() }) {
            if (p != null && (p.startsWith("_workspace/") || p.startsWith("resources/"))) return p;
        }
        return null;
    }

    private void trashVaultFile(String vaultRel) {
        try {
            String abs = Paths.get(settingsRepo.getVaultPath()).resolve(vaultRel).normalize().toString();
            fileRepo.softDeleteFile(abs);   // idempotent — no-op if already gone
        } catch (Exception e) {
            log.warn("[cleanup] could not trash {}: {}", vaultRel, e.toString());
        }
    }

    /** Runs on the capture lane — the SAFETY NET's per-tick batch. Claim-and-submit each
     *  still-'queued' row (never claimed by the instant Rabbit path, or that path's
     *  message was lost) exactly as attempt 0 — same as a fresh capture. */
    void drain() {
        if (!appReady) return;
        List<CaptureRepository.Capture> batch = captureRepo.findQueued(batchLimit);
        if (batch.isEmpty()) return;
        for (CaptureRepository.Capture c : batch) {
            if (!captureRepo.claim(c.id())) continue;   // a concurrent drain/listener won it
            processCapture(c, 0);
        }
    }

    /** The instant path: one message per capture, published by CaptureController's outbox
     *  chokepoint (attempt 0, header absent) or by the broker re-delivering a ladder rung
     *  after its TTL expires (attempt = the rung number, via {@code x-attempt-count}). */
    @RabbitListener(queues = RabbitQueueConfig.CAPTURE_QUEUE)
    public void onCaptureMessage(Message message) {
        String captureId = readCaptureId(message.getBody());
        if (captureId == null) return;
        int attempt = readAttempt(message);
        if (attempt == 0) {
            if (!captureRepo.claim(captureId)) return;   // lost the race, or not 'queued'
        }
        CaptureRepository.Capture c = captureRepo.get(captureId);
        // attempt 0 just claimed it, so this is really a null-safety guard there; for a
        // ladder redelivery (attempt>0) it's the real idempotency check — a manual dismiss,
        // a resolved dedup race, etc. may have moved the row off 'processing' already.
        if (c == null || !"processing".equals(c.status())) return;
        processCapture(c, attempt);
    }

    /** Shared by the poll-based safety net ({@link #drain()}) and the instant
     *  {@link #onCaptureMessage} listener: submit the already-claimed row, and route the
     *  outcome exactly like the old inline drain() body did: {@code ok} leaves it
     *  'processing' for the embedder to own; a 4xx dead-letters immediately (retrying
     *  won't help); a 5xx/transport failure advances to the next retry-ladder rung, or
     *  dead-letters once the ladder (3 rungs) is exhausted. */
    void processCapture(CaptureRepository.Capture c, int attempt) {
        IngestClient.Result res;
        try {
            res = submit(c);
        } catch (Exception e) {   // reconstruction/read error — treat like a transport failure
            log.warn("[CaptureIngestWorker] {} submit errored: {}", c.id(), e.toString());
            res = new IngestClient.Result(false, 0, null, e.toString());
        }

        if (res.ok()) {
            ridingLadder.remove(c.id());
            log.info("[CaptureIngestWorker] submitted {}", c.id());
            return;
        }
        if (res.status() >= 400 && res.status() < 500) {
            // the embedder rejected the request itself (bad ref/route) — immediate
            // retrying won't help; straight to dead-letter, skip the ladder entirely.
            deadLetter(c.id(), "rejected (" + res.status() + "): " + res.body());
            return;
        }
        // transport / 5xx — embedder down or busy; ride the retry ladder.
        int nextAttempt = attempt + 1;
        if (nextAttempt > RabbitQueueConfig.CAPTURE_RUNG_QUEUES.length) {
            deadLetter(c.id(), "retry ladder exhausted: " + res.body());
        } else {
            publishToRung(c.id(), nextAttempt);
        }
    }

    /** Publish onto the next backoff rung (1-indexed: rung 1 = capture.wait.1h, ...) with
     *  the attempt count carried forward as a message header — the broker's DLX+TTL moves
     *  it back onto {@code capture} once the rung's TTL expires (see RabbitQueueConfig). */
    private void publishToRung(String captureId, int rung) {
        ridingLadder.add(captureId);
        String queueName = RabbitQueueConfig.CAPTURE_RUNG_QUEUES[rung - 1];
        try {
            String payload = mapper.writeValueAsString(Map.of("id", captureId));
            rabbitTemplate.convertAndSend(queueName, payload,
                m -> { m.getMessageProperties().setHeader("x-attempt-count", rung); return m; });
            log.info("[CaptureIngestWorker] {} riding retry ladder → {} (attempt {})",
                captureId, queueName, rung);
        } catch (Exception e) {
            // Broker unreachable — don't strand the row silently; dead-letter it now rather
            // than lose the retry entirely (a manual retry always still works afterward).
            log.warn("[CaptureIngestWorker] could not publish {} to {}: {}", captureId, queueName, e.toString());
            deadLetter(captureId, "could not schedule retry: " + e.getMessage());
        }
    }

    /** Terminal handling for THIS submission attempt: publish to capture.deadletter so its
     *  listener ({@link #onCaptureDeadLetter}) performs the two dead-letter actions durably
     *  (survives a crash between "decided to give up" and actually recording it). Does NOT
     *  poison the capture forever — {@code markFailed} only stops the AUTOMATIC ladder; a
     *  manual {@code POST /api/capture/{id}/retry} re-enters the normal flow from rung 1. */
    private void deadLetter(String captureId, String error) {
        ridingLadder.remove(captureId);
        try {
            String payload = mapper.writeValueAsString(Map.of("id", captureId, "error", error));
            rabbitTemplate.convertAndSend(RabbitQueueConfig.CAPTURE_DEADLETTER_QUEUE, payload);
        } catch (Exception e) {
            // Broker unreachable even for the deadletter hop — fall back to handling it
            // inline so the failure is never silently lost (Phase 0's whole point).
            log.warn("[CaptureIngestWorker] could not publish dead-letter for {}: {}", captureId, e.toString());
            recordDeadLetter(captureId, error);
        }
    }

    /** Consumes {@code capture.deadletter}: the two actions the plan specifies, nothing
     *  else. (a) {@code markFailed} — the SAME method the pre-Rabbit code path already
     *  used, so {@code GET /api/capture/failed} and manual retry/dismiss are unchanged.
     *  (b) a {@code pipeline_failures} row, so debugging a capture dead-letter looks
     *  identical to debugging any other pipeline's failure. */
    @RabbitListener(queues = RabbitQueueConfig.CAPTURE_DEADLETTER_QUEUE)
    public void onCaptureDeadLetter(Message message) {
        String captureId;
        String error;
        try {
            JsonNode node = mapper.readTree(message.getBody());
            captureId = node.path("id").asText(null);
            error = node.path("error").asText(null);
        } catch (Exception e) {
            log.warn("[CaptureIngestWorker] malformed dead-letter message, dropping: {}", e.getMessage());
            return;
        }
        if (captureId == null) return;
        recordDeadLetter(captureId, error);
    }

    private void recordDeadLetter(String captureId, String error) {
        ridingLadder.remove(captureId);
        CaptureRepository.Capture c = captureRepo.get(captureId);
        captureRepo.markFailed(captureId, error);
        log.warn("[CaptureIngestWorker] {} dead-lettered: {}", captureId, error);
        pipelineFailureRepo.record("capture", "ingest_submit",
            Map.of("captureId", captureId,
                   "sourceRef", c != null && c.sourceRef() != null ? c.sourceRef() : "",
                   "sourceType", c != null && c.sourceType() != null ? c.sourceType() : ""),
            null, error, null);
    }

    private String readCaptureId(byte[] body) {
        try {
            return mapper.readTree(new String(body, StandardCharsets.UTF_8)).path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int readAttempt(Message message) {
        Object h = message.getMessageProperties().getHeaders().get("x-attempt-count");
        return h instanceof Number ? ((Number) h).intValue() : 0;
    }

    /** Rebuild the embedder payload from the persisted capture row. Text captures kept
     *  their prose on disk (CaptureController.storeTextResource); read it back so the
     *  embedder's text route gets the content, not just a path. */
    private IngestClient.Result submit(CaptureRepository.Capture c) throws Exception {
        if ("text".equals(c.sourceType())) {
            String text = readTextResource(c.sourcePath());
            if (text == null || text.isBlank()) {
                return new IngestClient.Result(false, 422, null, "text resource missing");
            }
            return ingestClient.submitText(c.id(), text, c.title());
        }
        return ingestClient.submitStandalone(c.id(), c.sourceRef(), c.sourceType());
    }

    private String readTextResource(String vaultRelPath) throws Exception {
        if (vaultRelPath == null) return null;
        Path file = Paths.get(settingsRepo.getVaultPath()).resolve(vaultRelPath).normalize();
        if (!Files.isRegularFile(file)) return null;
        return Files.readString(file);
    }
}
