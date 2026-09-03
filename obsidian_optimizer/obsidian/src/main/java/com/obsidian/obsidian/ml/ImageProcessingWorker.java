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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Tick: hand the drain to the image lane and return immediately. */
    @Scheduled(fixedDelay = 30_000)
    public void processPendingImages() {
        if (!enabled) return;
        lane.trigger(this::drain);
    }

    /** Runs on the image lane. One batch per tick — the 30s pacing is deliberate,
     *  since captioning hits rate-limited vision providers; a tight drain loop would
     *  hammer cooled providers. The blocking invokeAll now blocks the lane, not the
     *  scheduler thread, so embedding/cards/chrono keep running alongside it. */
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
     *  Prune first so orphan SKIPPED rows aren't pointlessly requeued. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public void requeueSkipped() {
        int pruned = jobRepo.pruneOrphans();
        if (pruned > 0) {
            log.info("[ImageProcessingWorker] pruned {} orphan image job(s) (embedding note gone)", pruned);
        }
        int requeued = jobRepo.requeueSkipped();
        if (requeued > 0) {
            log.info("[ImageProcessingWorker] requeued {} SKIPPED image job(s) for daily retry", requeued);
        }
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
