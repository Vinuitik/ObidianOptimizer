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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int SEARCH_LIMIT = 60;
    // Max chunks per /embed round-trip. The GPU embeds a batch almost as fast as a
    // single text, so batching collapses N per-chunk calls into ⌈N/64⌉. Bound keeps
    // the request body + GPU memory sane on notes with very many chunks.
    private static final int EMBED_BATCH = 64;

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

        // Pass 1: collect only the chunks whose text changed since last embed —
        // those are the ones we actually pay the GPU for. Unchanged chunks skip.
        List<NoteChunk> changed = new ArrayList<>();
        List<String> changedHashes = new ArrayList<>();
        for (NoteChunk chunk : chunks) {
            String newHash = ImageScanService.sha256(chunk.getText());
            String storedHash = chunkRepo.getContentHash(path, "text", chunk.getChunkIndex());
            if (newHash.equals(storedHash)) {
                continue;
            }
            changed.add(chunk);
            changedHashes.add(newHash);
        }

        // Pass 2: embed the changed chunks in batches — one HTTP/GPU round-trip per
        // EMBED_BATCH chunks instead of one per chunk. The embedder preserves order.
        boolean allOk = true;
        for (int start = 0; start < changed.size(); start += EMBED_BATCH) {
            int end = Math.min(start + EMBED_BATCH, changed.size());
            List<NoteChunk> slice = changed.subList(start, end);
            List<String> texts = new ArrayList<>(slice.size());
            for (NoteChunk c : slice) {
                texts.add(c.getText());
            }

            List<float[]> vectors = embedBatch(texts);
            if (vectors == null || vectors.size() != slice.size()) {
                log.warn("[EmbeddingService] batch embed failed for {} (chunks {}..{}) — will retry",
                    path, start, end);
                allOk = false;
                continue;   // leave this slice unembedded; the note stays in the diff
            }
            for (int i = 0; i < slice.size(); i++) {
                NoteChunk c = slice.get(i);
                chunkRepo.upsertChunk(path, c.getChunkIndex(), "text", c.getText(),
                    vectors.get(i), changedHashes.get(start + i));
            }
        }

        chunkRepo.deleteStaleChunks(path, "text", chunks.size() - 1);
        log.debug("[EmbeddingService] indexed {} text chunk(s) for {} ({} changed)",
            chunks.size(), path, changed.size());
        return allOk;
    }

    /**
     * Embeds a single text string. Thin delegate over {@link #embedBatch} so the
     * query path and any single-chunk caller share one transport implementation.
     * Returns null if the service is unreachable or returns an error.
     */
    public float[] embed(String text) {
        List<float[]> vectors = embedBatch(List.of(text));
        return (vectors == null || vectors.isEmpty()) ? null : vectors.get(0);
    }

    /**
     * Embeds a batch of texts in ONE request. Returns vectors in input order, or
     * null if the service is unreachable, errors, or the returned count doesn't
     * match the input (so the caller can leave the whole slice for a later retry).
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        final String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("texts", texts));
        } catch (Exception e) {
            log.warn("[EmbeddingService] embed serialize error: {}", e.getMessage());
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(embedderUrl + "/embed"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        // The JDK client pools keep-alive connections; if uvicorn closed one
        // between requests the first send fails ("header parser received no
        // bytes"). Retry the SEND once (fresh connection). We never retry after a
        // response is received, so a 200 is parsed exactly once — no double embed.
        IOException lastSendError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[EmbeddingService] batch embed interrupted");
                return null;
            } catch (IOException e) {
                lastSendError = e;   // likely a stale pooled connection — retry
                continue;
            }

            if (response.statusCode() != 200) {
                log.warn("[EmbeddingService] embedder returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            try {
                JsonNode embeddingsNode = objectMapper.readTree(response.body()).path("embeddings");
                if (!embeddingsNode.isArray() || embeddingsNode.size() != texts.size()) {
                    log.warn("[EmbeddingService] unexpected embedder response (wanted {} vectors): {}",
                        texts.size(), response.body());
                    return null;
                }
                List<float[]> out = new ArrayList<>(embeddingsNode.size());
                for (JsonNode vecNode : embeddingsNode) {
                    float[] vec = new float[vecNode.size()];
                    for (int i = 0; i < vec.length; i++) {
                        vec[i] = (float) vecNode.get(i).asDouble();
                    }
                    out.add(vec);
                }
                return out;
            } catch (Exception e) {
                log.warn("[EmbeddingService] embed parse error: {}", e.getMessage());
                return null;
            }
        }
        log.warn("[EmbeddingService] batch embed failed after retry: {}",
            lastSendError == null ? "unknown" : lastSendError.getMessage());
        return null;
    }

    /** Embeds a query string for search. */
    public float[] embedQuery(String query) {
        return embed(query);
    }

    public int getSearchLimit() {
        return SEARCH_LIMIT;
    }
}
