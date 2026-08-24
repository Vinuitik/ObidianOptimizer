package com.obsidian.obsidian.ml;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NEW API SURFACE (offline embeddings support). Returns cached note vectors so the
 * frontend can pull them into IndexedDB (frontend/src/pwa/db.js 'noteVectors' store)
 * piggybacked on the existing review sync — see frontend/src/pwa/syncOffline.js and
 * frontend/src/pwa/drivePull.js. There was no prior endpoint exposing a note's
 * embedding to the frontend; SearchController/EmbeddingService only ever used vectors
 * server-side for /search.
 *
 * Note: this returns a note's DOCUMENT-side vector (mean of its indexed text chunks).
 * It is NOT a query embedder — the embedder's model is prompt-asymmetric (kind:
 * "document" vs "query", see EmbeddingService.embedBatch), so this vector can only be
 * cosine-compared against OTHER document vectors, not against a freshly-typed search
 * query. There is no offline path to embed new query text (see FLOWS.md).
 */
@RestController
public class NoteVectorController {

    // Defensive cap — mirrors SearchController's limit clamp. The offline sync piggybacks
    // this on the review page (syncForOffline default limit=40), so real requests are small.
    private static final int MAX_PATHS = 500;

    private final NoteChunkRepository chunkRepo;

    public NoteVectorController(NoteChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
    }

    public record VectorsRequest(List<String> paths) {}

    /** Returns one averaged text-chunk vector per note that has embedded text chunks.
     *  Paths with nothing indexed yet are simply omitted — not an error. */
    @PostMapping("/notes/vectors")
    public List<NoteVector> vectors(@RequestBody VectorsRequest req) {
        List<String> paths = req.paths() == null ? List.of() : req.paths();
        if (paths.size() > MAX_PATHS) paths = paths.subList(0, MAX_PATHS);
        return chunkRepo.findAveragedTextVectors(paths);
    }
}
