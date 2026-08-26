package com.obsidian.obsidian.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final long TIMEOUT_MS = 5_000L;

    private final SearchService searchService;
    private final NoteEmbeddingWorker embeddingWorker;

    public SearchController(SearchService searchService, NoteEmbeddingWorker embeddingWorker) {
        this.searchService = searchService;
        this.embeddingWorker = embeddingWorker;
    }

    @GetMapping("/search")
    public DeferredResult<List<SearchResult>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {

        AtomicBoolean cancelled = new AtomicBoolean(false);

        // On timeout: mark cancelled and return empty list so the client isn't left hanging.
        DeferredResult<List<SearchResult>> deferred = new DeferredResult<>(TIMEOUT_MS, List.of());
        deferred.onTimeout(() -> {
            log.warn("[Search] request timed out after {}ms for query '{}'", TIMEOUT_MS, q);
            cancelled.set(true);
        });

        CompletableFuture.supplyAsync(() ->
                searchService.search(q, Math.min(limit, 20), cancelled)
        ).thenAccept(results -> {
            if (!cancelled.get()) deferred.setResult(results);
        }).exceptionally(ex -> {
            log.error("[Search] error during search for '{}'", q, ex);
            if (!cancelled.get()) deferred.setErrorResult(ex);
            return null;
        });

        return deferred;
    }

    /**
     * One-off: backfill title chunks (source='title') for every note that doesn't
     * have one yet, so old notes get the same "exact-name-wins" search boost new
     * notes get automatically. Idempotent — safe to call again; a run already in
     * flight is reported rather than stacked. Runs on the background embed lane,
     * so this returns immediately without waiting for the scan to finish.
     */
    @PostMapping("/search/reindex-titles")
    public Map<String, Object> reindexTitles() {
        boolean started = embeddingWorker.backfillTitleChunks();
        return Map.of("started", started);
    }
}
