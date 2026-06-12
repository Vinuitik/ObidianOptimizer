package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Value("${image.worker.parallelism:4}")
    private int parallelism;

    private final PendingImageJobRepository jobRepo;
    private final EmbeddingService embeddingService;
    private final NoteChunkRepository chunkRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ExecutorService pool;

    public ImageProcessingWorker(PendingImageJobRepository jobRepo,
                                 EmbeddingService embeddingService,
                                 NoteChunkRepository chunkRepo) {
        this.jobRepo          = jobRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;
    }

    @PostConstruct
    void initPool() {
        pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
    }

    @PreDestroy
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Scheduled(fixedDelay = 30_000)
    public void processPendingImages() {
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
                jobs.forEach(this::processJob);
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

    /** Safety net: SKIPPED jobs (permanent-looking failures) get one retry per day. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public void requeueSkipped() {
        int requeued = jobRepo.requeueSkipped();
        if (requeued > 0) {
            log.info("[ImageProcessingWorker] requeued {} SKIPPED image job(s) for daily retry", requeued);
        }
    }

    private void processJob(PendingImageJob job) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("image_path", job.getImagePath()));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/process-image"))
                .timeout(WRAPPER_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                // image file gone — permanent until the note changes; daily requeue covers it
                log.warn("[ImageProcessingWorker] image not found for {} — marking SKIPPED", job.getImagePath());
                jobRepo.markSkipped(job.getId());
                return;
            }
            if (response.statusCode() != 200) {
                // transient (503 = all LLM providers exhausted/cooling) — keep PENDING, retry next cycle
                log.warn("[ImageProcessingWorker] wrapper returned HTTP {} for {} — leaving PENDING for retry",
                    response.statusCode(), job.getImagePath());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String extractedText = root.path("text").asText("").trim();
            String provider = root.path("provider").asText("unknown");

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
                String hash = ImageScanService.sha256(chunk);
                float[] embedding = embeddingService.embed(chunk);

                if (embedding != null) {
                    chunkRepo.upsertChunk(job.getNotePath(), imageChunkStartIndex + i, "image", chunk, embedding, hash);
                }
            }

            jobRepo.markDone(job.getId());
            log.debug("[ImageProcessingWorker] processed {} via {} -> {} chunk(s)",
                job.getImagePath(), provider, textChunks.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ImageProcessingWorker] interrupted during job {}", job.getId());
        } catch (Exception e) {
            // network/timeout — transient, keep PENDING so the multi-day backlog never drops images
            log.warn("[ImageProcessingWorker] failed job {} ({}) — leaving PENDING for retry: {}",
                job.getId(), job.getImagePath(), e.getMessage());
        }
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
