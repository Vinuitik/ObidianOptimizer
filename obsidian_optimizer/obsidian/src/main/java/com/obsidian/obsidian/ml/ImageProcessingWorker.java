package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.common.OutboxRepository;
import com.obsidian.obsidian.common.RabbitQueueConfig;
import com.obsidian.obsidian.common.WorkerLane;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ImageProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingWorker.class);

    // VLM output longer than this gets chunked before embedding
    static final int IMAGE_CHUNK_THRESHOLD = 1000;
    private static final int IMAGE_CHUNK_OVERLAP = 200;
    private static final int BATCH_SIZE = 10;
    // Router acquire deadline (150s) + LLM request (120s) + margin
    private static final Duration WRAPPER_TIMEOUT = Duration.ofSeconds(300);

    @Value("${wrapper.url:http://host.docker.internal:5001}")
    private String wrapperUrl;

    // Quiet mode: false → no image captioning runs (boot the app light for testing).
    @Value("${images.enabled:true}")
    private boolean enabled;

    @Value("${image.worker.parallelism:4}")
    private int parallelism;

    // Images per wrapper request (per note). The wrapper sub-batches further to
    // each provider's calibrated LLM_VISION_BATCH limit — see host-wrapper/FLOWS.md.
    @Value("${image.batch.size:4}")
    private int imageBatchSize;

    private final PendingImageJobRepository jobRepo;
    private final EmbeddingService embeddingService;
    private final NoteChunkRepository chunkRepo;
    private final OutboxRepository outboxRepo;
    // Programmatic, not @Transactional: handleResult (below) is only ever called via
    // self-invocation (processJobBatch -> handleResult on `this`), which bypasses
    // Spring's AOP proxy entirely — an annotation here would silently never apply.
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP_1_1: uvicorn embedder can't do the JDK client's default h2c upgrade,
    // which drops POST bodies (422). See ResourceScanService for the full detail.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).build();
    private ExecutorService pool;

    // Outer lane: the drain runs here so its blocking invokeAll can't hold the
    // shared scheduler thread. `pool` (below) is the INNER per-note parallelism.
    private final WorkerLane lane = new WorkerLane("image");

    public ImageProcessingWorker(PendingImageJobRepository jobRepo,
                                 EmbeddingService embeddingService,
                                 NoteChunkRepository chunkRepo,
                                 OutboxRepository outboxRepo,
                                 PlatformTransactionManager txManager) {
        this.jobRepo          = jobRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;
        this.outboxRepo       = outboxRepo;
        this.txTemplate       = new TransactionTemplate(txManager);
    }

    @PostConstruct
    void initPool() {
        pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
    }

    @PreDestroy
    void shutdownPool() {
        lane.shutdown();
        pool.shutdownNow();
    }

    /** Tick: hand the drain to the image lane and return immediately. Now the SAFETY
     *  NET (default 1h, was 30s) — the outbox+RabbitMQ path from ImageScanService's
     *  chokepoint is the primary trigger, delivering within seconds instead of waiting
     *  for this tick. See QUEUE_UNIFICATION_PLAN.md Phase 5. */
    @Scheduled(fixedDelayString = "${image.scan.delay-ms:3600000}",
               initialDelayString = "${image.scan.initial-delay-ms:30000}")
    public void processPendingImages() {
        if (!enabled) return;
        lane.trigger(this::drain);
    }

    /** Runs on the image lane. One batch per tick — deliberate pacing even now that
     *  it's a safety net, since captioning hits rate-limited vision providers; a tight
     *  drain loop would hammer cooled providers. The blocking invokeAll now blocks the
     *  lane, not the scheduler thread, so embedding/cards/chrono keep running alongside it. */
    void drain() {
        List<PendingImageJob> batch = jobRepo.findPending(BATCH_SIZE);
        if (batch.isEmpty()) return;

        log.info("[ImageProcessingWorker] processing {} pending image job(s)", batch.size());

        boolean wrapperAvailable = checkWrapperHealth();
        if (!wrapperAvailable) {
            log.warn("[ImageProcessingWorker] host wrapper unreachable at {} — skipping batch", wrapperUrl);
            return;
        }

        // Parallelize across NOTES, not jobs: images of the same note must run
        // sequentially because getNextChunkIndex() would collide otherwise.
        // Concurrent requests let the wrapper's LLM router shard across
        // providers (image A → Gemini while image B → Groq).
        Map<String, List<PendingImageJob>> byNote = new LinkedHashMap<>();
        for (PendingImageJob job : batch) {
            byNote.computeIfAbsent(job.getNotePath(), k -> new ArrayList<>()).add(job);
        }

        List<Callable<Void>> tasks = byNote.values().stream()
            .map(jobs -> (Callable<Void>) () -> {
                // same-note images batch into ONE wrapper request (they are
                // sequential anyway), in slices of imageBatchSize
                for (int i = 0; i < jobs.size(); i += Math.max(1, imageBatchSize)) {
                    processJobBatch(jobs.subList(i,
                        Math.min(jobs.size(), i + Math.max(1, imageBatchSize))));
                }
                return null;
            })
            .toList();

        try {
            pool.invokeAll(tasks); // block so fixedDelay paces batches correctly
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ImageProcessingWorker] interrupted while waiting for batch");
        }
    }

    /** Daily maintenance: drop orphan jobs (note gone → "stored by no one"), then
     *  give the remaining SKIPPED jobs (permanent-looking failures) another retry.
     *  Prune first so orphan SKIPPED rows aren't pointlessly requeued.
     *
     * <p>Revived rows also get a fresh outbox publish, one per id — requeuing to
     * PENDING alone would otherwise sit invisible until the (now-hourly) poll safety
     * net happens to notice it, instead of being picked up by the listener in seconds
     * like every other path onto this queue. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public void requeueSkipped() {
        int pruned = jobRepo.pruneOrphans();
        if (pruned > 0) {
            log.info("[ImageProcessingWorker] pruned {} orphan image job(s) (embedding note gone)", pruned);
        }
        List<String> requeuedIds = jobRepo.requeueSkipped();
        for (String id : requeuedIds) {
            outboxRepo.enqueue(ImageCaptionQueueConfig.IMAGE_CAPTION_QUEUE, Map.of("jobId", id));
        }
        if (!requeuedIds.isEmpty()) {
            log.info("[ImageProcessingWorker] requeued {} SKIPPED image job(s) for daily retry", requeuedIds.size());
        }
    }

    /**
     * Fast path: consumes the "image-caption" outbox queue, one image per message.
     * Reuses {@link #handleResult} — the SAME per-job logic the poll fallback uses via
     * {@link #processJobBatch} — the only difference is the per-message error handling
     * needed to drive the retry-wait ladder (see {@link ImageCaptionQueueConfig}).
     *
     * <p>Concurrency = {@code image.worker.parallelism} (same property that sizes the
     * poll fallback's inner thread pool), so both paths pull the same total weight
     * against the wrapper's rate-limited providers.
     *
     * <p>Re-reads the job row fresh by id rather than trusting the message payload —
     * the idempotency check: a stale/redelivered message for an already-DONE/SKIPPED
     * job (poll fallback or another delivery got there first) is a safe no-op (ack,
     * no re-caption). A missing row (note deleted, pruneOrphans ran) is likewise a
     * safe no-op.
     */
    @RabbitListener(queues = ImageCaptionQueueConfig.IMAGE_CAPTION_QUEUE,
                     concurrency = "${image.worker.parallelism:4}")
    public void onImageCaptionMessage(String payloadJson) {
        if (!enabled) {
            // Boot-light/testing mode: park the message and try again later rather
            // than dropping it — mirrors the poll fallback's "just skip this tick".
            throw new AmqpRejectAndDontRequeueException("image captioning disabled");
        }
        String jobId;
        try {
            jobId = objectMapper.readTree(payloadJson).path("jobId").asText(null);
        } catch (Exception e) {
            log.warn("[ImageProcessingWorker] malformed image-caption message, dropping: {}", e.getMessage());
            return;
        }
        if (jobId == null) return;

        PendingImageJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || !"PENDING".equals(job.getStatus())) {
            return; // already handled, or the note/job is gone — nothing to do
        }

        try {
            JsonNode result = callProcessImage(job.getImagePath());
            String provider = result.path("provider").asText("unknown");
            handleResult(job, result, provider);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpRejectAndDontRequeueException("interrupted", e);
        } catch (Exception e) {
            // network/timeout/non-200 that isn't a clear "image gone" — transient.
            // Reject (not requeue-into-self, which would tight-loop) so it dead-letters
            // into the wait queue and comes back after image.caption.retry.wait-ms.
            log.warn("[ImageProcessingWorker] fast-path caption failed for {} — retrying via wait queue: {}",
                job.getImagePath(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("transient caption failure", e);
        }
    }

    /** Calls the wrapper's single-image endpoint and normalizes its response into the
     *  same shape {@link #handleResult} already expects from the batch endpoint
     *  ({@code {"text":...}} / {@code {"error":"not_found"}}) — the wrapper's actual
     *  404 body is {@code {"error":"not_found: <path>"}}, so that translation happens
     *  here rather than duplicating handleResult's error-matching. Throws on anything
     *  else non-200 or a transport failure; the caller decides retry semantics. */
    private JsonNode callProcessImage(String imagePath) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(Map.of("image_path", imagePath));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(wrapperUrl + "/process-image"))
            .timeout(WRAPPER_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return objectMapper.createObjectNode().put("error", "not_found");
        }
        if (response.statusCode() != 200) {
            throw new IOException("wrapper returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private void processJobBatch(List<PendingImageJob> jobs) {
        try {
            List<String> paths = jobs.stream().map(PendingImageJob::getImagePath).toList();
            String body = objectMapper.writeValueAsString(Map.of("image_paths", paths));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/process-images"))
                .timeout(WRAPPER_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // transient (503 = all LLM providers exhausted/cooling) — keep PENDING, retry next cycle
                log.warn("[ImageProcessingWorker] wrapper returned HTTP {} for {} image(s) — leaving PENDING for retry",
                    response.statusCode(), jobs.size());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            String provider = root.path("provider").asText("unknown");

            for (int i = 0; i < jobs.size() && i < results.size(); i++) {
                handleResult(jobs.get(i), results.get(i), provider);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ImageProcessingWorker] interrupted during batch of {}", jobs.size());
        } catch (Exception e) {
            // network/timeout — transient, keep PENDING so the multi-day backlog never drops images
            log.warn("[ImageProcessingWorker] batch of {} failed — leaving PENDING for retry: {}",
                jobs.size(), e.getMessage());
        }
    }

    void handleResult(PendingImageJob job, JsonNode result, String provider) {
        if ("not_found".equals(result.path("error").asText(null))) {
            // image file gone — permanent until the note changes; daily requeue covers it
            log.warn("[ImageProcessingWorker] image not found for {} — marking SKIPPED", job.getImagePath());
            jobRepo.markSkipped(job.getId());
            return;
        }

        String extractedText = result.path("text").asText("").trim();
        if (extractedText.isEmpty()) {
            log.debug("[ImageProcessingWorker] empty text for {} — marking DONE", job.getImagePath());
            jobRepo.markDone(job.getId());
            return;
        }

        // Image chunks live in their own source='image' index range — appended
        // after this note's existing image chunks, independent of text chunks
        int imageChunkStartIndex = getNextChunkIndex(job.getNotePath());

        List<String> textChunks = splitImageText(extractedText);
        for (int i = 0; i < textChunks.size(); i++) {
            String chunk = textChunks.get(i);
            String hash = ContentHashing.sha256(chunk);
            int chunkIndex = imageChunkStartIndex + i;

            // ALWAYS persist the caption text first — the VLM call is the expensive,
            // rate-limited part and must never be lost to a cheap embed hiccup.
            // Then best-effort embed inline; on failure the chunk stays NULL-vector
            // and the embed reconciler backfills it later. Either way the caption is safe.
            float[] embedding = embeddingService.embed(chunk);
            if (embedding != null) {
                chunkRepo.upsertChunk(job.getNotePath(), chunkIndex, "image", chunk, embedding, hash);
            } else {
                persistPendingChunkAndEnqueueEmbed(job.getNotePath(), chunkIndex, chunk, hash);
            }
        }

        // DONE = "captioned" (VLM work banked). Embedding is a SEPARATE queue now
        // (null-vector chunks), so a failed embed no longer means the job retries —
        // and never means the caption is lost.
        jobRepo.markDone(job.getId());
        log.debug("[ImageProcessingWorker] captioned {} via {} -> {} chunk(s)",
            job.getImagePath(), provider, textChunks.size());
    }

    /**
     * Persists a caption chunk with no vector yet, and enqueues the outbox "embed-chunk"
     * fast path, atomically — a crash between the two would otherwise either publish a
     * message for a chunk that never landed, or silently lose the fast-path signal for
     * one that did. ChunkEmbeddingReconciler's poll (now a much-slower safety net, see
     * {@code embedding.reconcile.delay-ms}) still finds anything this misses.
     */
    private void persistPendingChunkAndEnqueueEmbed(String notePath, int chunkIndex, String text, String hash) {
        txTemplate.executeWithoutResult(status -> {
            chunkRepo.upsertChunkTextOnly(notePath, chunkIndex, "image", text, hash);
            outboxRepo.enqueue(RabbitQueueConfig.EMBED_CHUNK_QUEUE, Map.of(
                "notePath", notePath, "source", "image", "chunkIndex", chunkIndex));
        });
    }

    private boolean checkWrapperHealth() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private int getNextChunkIndex(String notePath) {
        // Highest existing image-chunk index + 1 (text chunks are a separate range)
        Integer max = chunkRepo.queryMaxChunkIndex(notePath, "image");
        return max == null ? 0 : max + 1;
    }

    /** Splits large VLM output into overlapping chunks. */
    static List<String> splitImageText(String text) {
        if (text.length() <= IMAGE_CHUNK_THRESHOLD) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + IMAGE_CHUNK_THRESHOLD, text.length());
            chunks.add(text.substring(start, end));
            start += IMAGE_CHUNK_THRESHOLD - IMAGE_CHUNK_OVERLAP;
        }
        return chunks;
    }
}
