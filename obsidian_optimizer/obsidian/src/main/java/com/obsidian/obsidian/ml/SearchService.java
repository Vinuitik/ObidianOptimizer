package com.obsidian.obsidian.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int RRF_K = 60;
    // A note's own title chunk (source='title') means the query IS (close to) the
    // note's name — that should win over every other note that merely mentions it
    // in a [[wikilink]]. Multiplies that chunk's RRF contribution in each channel.
    private static final double TITLE_BOOST = 3.0;

    private final NoteChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;

    public SearchService(NoteChunkRepository chunkRepo, EmbeddingService embeddingService) {
        this.chunkRepo        = chunkRepo;
        this.embeddingService = embeddingService;
    }

    public List<SearchResult> search(String query, int limit, AtomicBoolean cancelled) {
        int fetchLimit = embeddingService.getSearchLimit();

        // Step 1: embed query + vector search (the expensive HTTP call is inside here)
        List<NoteChunk> vectorMatches = getVectorRankedMatches(query, fetchLimit);

        // Cheap checkpoint: if the client already left (timeout fired), skip BM25
        if (cancelled.get()) {
            log.debug("[Search] cancelled after vector step — skipping BM25 for '{}'", query);
            return List.of();
        }

        List<NoteChunk> textMatches   = getTextRankedMatches(query, fetchLimit);

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, NoteChunk> chunkMap = new HashMap<>();

        for (int i = 0; i < vectorMatches.size(); i++) {
            NoteChunk match = vectorMatches.get(i);
            String key = match.getNotePath() + "::" + match.getChunkIndex();
            double score = 1.0 / (RRF_K + i + 1);
            if ("title".equals(match.getSource())) score *= TITLE_BOOST;
            rrfScores.merge(key, score, Double::sum);
            chunkMap.putIfAbsent(key, match);
        }

        for (int i = 0; i < textMatches.size(); i++) {
            NoteChunk match = textMatches.get(i);
            String key = match.getNotePath() + "::" + match.getChunkIndex();
            double score = 1.0 / (RRF_K + i + 1);
            if ("title".equals(match.getSource())) score *= TITLE_BOOST;
            rrfScores.merge(key, score, Double::sum);
            chunkMap.putIfAbsent(key, match);
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted) {
            NoteChunk chunk = chunkMap.get(entry.getKey());
            boolean seen = results.stream().anyMatch(r -> r.getNotePath().equals(chunk.getNotePath()));
            if (!seen) {
                SearchResult res = new SearchResult();
                res.setNotePath(chunk.getNotePath());
                String snippet = chunk.getText().length() > 150
                    ? chunk.getText().substring(0, 150) + "..."
                    : chunk.getText();
                res.setSnippet(snippet);
                res.setScore(entry.getValue());
                results.add(res);
                if (results.size() >= limit) break;
            }
        }
        return results;
    }

    private List<NoteChunk> getVectorRankedMatches(String query, int limit) {
        float[] queryVec = embeddingService.embedQuery(query);
        if (queryVec == null) return List.of();
        return chunkRepo.findByVectorSimilarity(queryVec, limit);
    }

    private List<NoteChunk> getTextRankedMatches(String query, int limit) {
        return chunkRepo.findByTextSearch(query, limit);
    }
}
