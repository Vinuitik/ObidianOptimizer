package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Embeds note TEXT into note_chunks (source='text'). Same philosophy as the
 * cards worker: the notes.content_hash ↔ notes.embedded_hash diff IS the work
 * list — covers note creation, app edits, sync downloads, chrono rewrites,
 * external Obsidian edits (hash loop), and first-boot backfill with zero
 * call-site hooks. Image chunks are owned by ImageProcessingWorker.
 */
@Component
public class NoteEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(NoteEmbeddingWorker.class);
    private static final int BATCH_SIZE = 20;

    private final NoteIndexRepository noteIndexRepo;
    private final EmbeddingService embeddingService;
    private final NoteChunkRepository chunkRepo;

    public NoteEmbeddingWorker(NoteIndexRepository noteIndexRepo,
                               EmbeddingService embeddingService,
                               NoteChunkRepository chunkRepo) {
        this.noteIndexRepo    = noteIndexRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void embedPendingNotes() {
        Map<String, String> pending = noteIndexRepo.findNotesNeedingEmbedding(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.info("[NoteEmbeddingWorker] embedding {} note(s)", pending.size());

        int ok = 0;
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            try {
                if (embeddingService.indexNote(entry.getKey())) {
                    noteIndexRepo.markEmbedded(entry.getKey(), entry.getValue());
                    ok++;
                }
                // false → embedder unreachable / partial failure: stays in the
                // diff and is retried next cycle
            } catch (Exception e) {
                log.warn("[NoteEmbeddingWorker] failed {}: {}", entry.getKey(), e.getMessage());
            }
        }
        log.debug("[NoteEmbeddingWorker] embedded {}/{} note(s)", ok, pending.size());
    }

    /** Daily: drop chunks belonging to deleted/renamed notes (text AND image). */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 7_200_000)
    public void purgeOrphanChunks() {
        try {
            int purged = chunkRepo.deleteOrphanChunks();
            if (purged > 0) {
                log.info("[NoteEmbeddingWorker] purged {} orphan chunk(s) of deleted/renamed notes", purged);
            }
        } catch (Exception e) {
            log.warn("[NoteEmbeddingWorker] orphan purge failed: {}", e.getMessage());
        }
    }
}
