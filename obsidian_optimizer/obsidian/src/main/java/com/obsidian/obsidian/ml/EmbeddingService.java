package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int SEARCH_LIMIT = 60;

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    private final NoteChunkRepository chunkRepo;
    private final MarkdownPreprocessor preprocessor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP_1_1: uvicorn embedder can't do the JDK client's default h2c upgrade,
    // which drops POST bodies (422). See ResourceScanService for the full detail.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).build();

    public EmbeddingService(NoteChunkRepository chunkRepo, MarkdownPreprocessor preprocessor) {
        this.chunkRepo    = chunkRepo;
        this.preprocessor = preprocessor;
    }

    /**
     * Full index pipeline for a single note's TEXT chunks:
     * preprocess → chunk → hash-check → embed → upsert. Image chunks
     * (source='image') are owned by ImageProcessingWorker and untouched here.
     *
     * @return true when every chunk is indexed (or unchanged) — false on any
     *         read/embed failure so the caller can retry the note later.
     */
    public boolean indexNote(String path) {
        String content;
        try {
            content = Files.readString(Paths.get(path));
        } catch (IOException e) {
            log.warn("[EmbeddingService] cannot read {}: {}", path, e.getMessage());
            return false;
        }

        List<NoteChunk> chunks = preprocessor.chunkNote(path, content);
        boolean allOk = true;

        for (NoteChunk chunk : chunks) {
            String newHash = ImageScanService.sha256(chunk.getText());
            String storedHash = chunkRepo.getContentHash(path, "text", chunk.getChunkIndex());
            if (newHash.equals(storedHash)) {
                continue;
            }

            float[] embedding = embed(chunk.getText());
            if (embedding == null) {
                log.warn("[EmbeddingService] embed failed for {}#{} — will retry", path, chunk.getChunkIndex());
                allOk = false;
                continue;
            }

            chunkRepo.upsertChunk(path, chunk.getChunkIndex(), "text", chunk.getText(), embedding, newHash);
        }

        chunkRepo.deleteStaleChunks(path, "text", chunks.size() - 1);
        log.debug("[EmbeddingService] indexed {} text chunk(s) for {}", chunks.size(), path);
        return allOk;
    }

    /**
     * Embeds a single text string via the embedder service.
     * Returns null if the service is unreachable or returns an error.
     */
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("texts", List.of(text)));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/embed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[EmbeddingService] embedder returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingsNode = root.path("embeddings");
            if (embeddingsNode.isMissingNode() || !embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
                log.warn("[EmbeddingService] unexpected embedder response: {}", response.body());
                return null;
            }

            JsonNode firstVec = embeddingsNode.get(0);
            float[] vec = new float[firstVec.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) firstVec.get(i).asDouble();
            }
            return vec;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[EmbeddingService] embed interrupted");
            return null;
        } catch (Exception e) {
            log.warn("[EmbeddingService] embed error: {}", e.getMessage());
            return null;
        }
    }

    /** Embeds a query string for search. */
    public float[] embedQuery(String query) {
        return embed(query);
    }

    public int getSearchLimit() {
        return SEARCH_LIMIT;
    }
}
