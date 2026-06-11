package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ImageProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingWorker.class);

    // VLM output longer than this gets chunked before embedding
    static final int IMAGE_CHUNK_THRESHOLD = 1000;
    private static final int IMAGE_CHUNK_OVERLAP = 200;
    private static final int BATCH_SIZE = 10;

    @Value("${wrapper.url:http://host.docker.internal:5001}")
    private String wrapperUrl;

    private final PendingImageJobRepository jobRepo;
    private final EmbeddingService embeddingService;
    private final NoteChunkRepository chunkRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ImageProcessingWorker(PendingImageJobRepository jobRepo,
                                 EmbeddingService embeddingService,
                                 NoteChunkRepository chunkRepo) {
        this.jobRepo          = jobRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;
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

        for (PendingImageJob job : batch) {
            processJob(job);
        }
    }

    private void processJob(PendingImageJob job) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("image_path", job.getImagePath()));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/process-image"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[ImageProcessingWorker] wrapper returned HTTP {} for {} — marking SKIPPED",
                    response.statusCode(), job.getImagePath());
                jobRepo.markSkipped(job.getId());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String extractedText = root.path("text").asText("").trim();

            if (extractedText.isEmpty()) {
                log.debug("[ImageProcessingWorker] empty text for {} — marking DONE", job.getImagePath());
                jobRepo.markDone(job.getId());
                return;
            }

            // Determine starting chunk_index after existing text chunks for this note
            int imageChunkStartIndex = getNextChunkIndex(job.getNotePath());

            List<String> textChunks = splitImageText(extractedText);
            for (int i = 0; i < textChunks.size(); i++) {
                String chunk = textChunks.get(i);
                String hash = ImageScanService.sha256(chunk);
                float[] embedding = embeddingService.embed(chunk);

                if (embedding != null) {
                    chunkRepo.upsertChunk(job.getNotePath(), imageChunkStartIndex + i, chunk, embedding, hash);
                }
            }

            jobRepo.markDone(job.getId());
            log.debug("[ImageProcessingWorker] processed {} -> {} chunk(s)", job.getImagePath(), textChunks.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ImageProcessingWorker] interrupted during job {}", job.getId());
        } catch (Exception e) {
            log.warn("[ImageProcessingWorker] failed job {} ({}): {}", job.getId(), job.getImagePath(), e.getMessage());
            jobRepo.markSkipped(job.getId());
        }
    }

    private boolean checkWrapperHealth() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/health"))
                .GET()
                .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private int getNextChunkIndex(String notePath) {
        // Image chunks are appended after text chunks — find the highest existing index + 1
        try {
            Integer max = chunkRepo.queryMaxChunkIndex(notePath);
            return max == null ? 0 : max + 1;
        } catch (Exception e) {
            return 10_000; // safe offset: image chunks start well after text chunks
        }
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
