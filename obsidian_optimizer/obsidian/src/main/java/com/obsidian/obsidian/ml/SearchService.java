package com.obsidian.obsidian.ml;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    // K is a standard constant commonly set to 60 for Reciprocal Rank Fusion
    private static final int RRF_K = 60;

    /**
     * Stubs out the Hybrid Search with Reciprocal Rank Fusion (RRF).
     * In the future, this will query pgvector for embeddings and postgres FTS for BM25.
     */
    public List<SearchResult> search(String query, int limit) {
        
        // 1. Fetch from Vector Search (Stub - later calls EmbeddingService/pgvector)
        List<NoteChunk> vectorMatches = getVectorRankedMatches(query, limit);
        
        // 2. Fetch from BM25 Text Search (Stub - later calls pg_search/FTS)
        List<NoteChunk> textMatches = getTextRankedMatches(query, limit);

        // 3. Compute RRF Scores
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, NoteChunk> chunkMap = new HashMap<>(); 

        double wVector = 0.7; // Weights from ML_ARCH.md
        double wText = 0.3;

        // Rank Vector matches
        for (int i = 0; i < vectorMatches.size(); i++) {
            NoteChunk match = vectorMatches.get(i);
            int rank = i + 1;
            double rrfScore = wVector * (1.0 / (RRF_K + rank));
            String key = match.getNotePath() + "::" + match.getChunkIndex();
            
            rrfScores.put(key, rrfScores.getOrDefault(key, 0.0) + rrfScore);
            chunkMap.putIfAbsent(key, match);
        }

        // Rank Text matches
        for (int i = 0; i < textMatches.size(); i++) {
            NoteChunk match = textMatches.get(i);
            int rank = i + 1;
            double rrfScore = wText * (1.0 / (RRF_K + rank));
            String key = match.getNotePath() + "::" + match.getChunkIndex();

            rrfScores.put(key, rrfScores.getOrDefault(key, 0.0) + rrfScore);
            chunkMap.putIfAbsent(key, match);
        }

        // 4. Sort by RRF Score descending
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 5. Build results & deduplicate by note path (take best snippet per note)
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sortedEntries) {
            NoteChunk chunk = chunkMap.get(entry.getKey());
            
            boolean alreadyHasNote = results.stream().anyMatch(r -> r.getNotePath().equals(chunk.getNotePath()));
            if (!alreadyHasNote) {
                SearchResult res = new SearchResult();
                res.setNotePath(chunk.getNotePath());
                
                // Truncate snippet
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
        // STUB: Returns empty for now.
        return new ArrayList<>(); 
    }

    private List<NoteChunk> getTextRankedMatches(String query, int limit) {
        // STUB: Returns empty for now.
        return new ArrayList<>();
    }
}
