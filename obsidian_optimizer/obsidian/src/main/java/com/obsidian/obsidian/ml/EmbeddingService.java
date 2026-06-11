package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.settings.SettingsRepository;
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

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int SEARCH_LIMIT = 60;

    @Value("${ollama.base.url:http://localhost:11434}")
    private String ollamaBaseUrl;

    private final SettingsRepository settingsRepo;
    private final NoteChunkRepository chunkRepo;
    private final MarkdownPreprocessor preprocessor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public EmbeddingService(SettingsRepository settingsRepo,
                            NoteChunkRepository chunkRepo,
                            MarkdownPreprocessor preprocessor) {
        this.settingsRepo = settingsRepo;
        this.chunkRepo    = chunkRepo;
        this.preprocessor = preprocessor;
    }

    /**
     * Full index pipeline for a single note.
     * Called after note create/update and from ImageProcessingWorker after image text is ready.
     */
    public void indexNote(String path) {
        String content;
        try {
            content = Files.readString(Paths.get(path));
        } catch (IOException e) {
            log.warn("[EmbeddingService] cannot read {}: {}", path, e.getMessage());
            return;
        }

        String model = settingsRepo.getEmbedModel();
        List<NoteChunk> chunks = preprocessor.chunkNote(path, content);

        for (NoteChunk chunk : chunks) {
            String newHash = ImageScanService.sha256(chunk.getText());
            String storedHash = chunkRepo.getContentHash(path, chunk.getChunkIndex());
            if (newHash.equals(storedHash)) {
                continue; // unchanged — skip Ollama call
            }

            float[] embedding = embed(chunk.getText(), model);
            if (embedding == null) {
                log.warn("[EmbeddingService] embed failed for {}#{} — skipping", path, chunk.getChunkIndex());
                continue;
            }

            chunkRepo.upsertChunk(path, chunk.getChunkIndex(), chunk.getText(), embedding, newHash);
        }

        chunkRepo.deleteStaleChunks(path, chunks.size() - 1);
        log.debug("[EmbeddingService] indexed {} chunk(s) for {}", chunks.size(), path);
    }

    /**
     * Embeds a single text string. Returns null if Ollama is unreachable or returns an error.
     */
    public float[] embed(String text, String model) {
        try {
            String body = objectMapper.writeValueAsString(
                java.util.Map.of("model", model, "prompt", text));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[EmbeddingService] Ollama returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embNode = root.path("embedding");
            if (embNode.isMissingNode() || !embNode.isArray()) {
                log.warn("[EmbeddingService] unexpected Ollama response shape: {}", response.body());
                return null;
            }

            float[] vec = new float[embNode.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embNode.get(i).asDouble();
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

    /** Embeds a query string for search (uses current model from settings). */
    public float[] embedQuery(String query) {
        return embed(query, settingsRepo.getEmbedModel());
    }

    public int getSearchLimit() {
        return SEARCH_LIMIT;
    }

    public String getModel() {
        return settingsRepo.getEmbedModel();
    }
}
