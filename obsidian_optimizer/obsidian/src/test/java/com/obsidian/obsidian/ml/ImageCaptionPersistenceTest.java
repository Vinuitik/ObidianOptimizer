package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The caption must survive an embed failure. Before the caption/embed split, a
 * failed embed dropped the chunk AND the job was marked DONE → the expensive VLM
 * caption was lost forever. Now the caption text is always persisted (NULL vector)
 * and the embed reconciler backfills it.
 */
class ImageCaptionPersistenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ImageProcessingWorker worker(PendingImageJobRepository jobRepo,
                                         EmbeddingService emb, NoteChunkRepository chunkRepo) {
        var w = new ImageProcessingWorker(jobRepo, emb, chunkRepo);
        when(chunkRepo.queryMaxChunkIndex(anyString(), eq("image"))).thenReturn(null); // start at 0
        return w;
    }

    @Test
    void embedFailure_stillPersistsCaption_andMarksDone() throws Exception {
        var jobRepo = mock(PendingImageJobRepository.class);
        var emb = mock(EmbeddingService.class);
        var chunkRepo = mock(NoteChunkRepository.class);
        var worker = worker(jobRepo, emb, chunkRepo);

        when(emb.embed(anyString())).thenReturn(null);  // embedder down at this moment

        var job = new PendingImageJob("notes/x.md", "img.png");
        job.setId("job1");
        worker.handleResult(job, mapper.readTree("{\"text\":\"a real caption\"}"), "mistral");

        // caption saved as text-only (NULL vector) — NOT dropped
        verify(chunkRepo).upsertChunkTextOnly(eq("notes/x.md"), eq(0), eq("image"), eq("a real caption"), anyString());
        verify(chunkRepo, never()).upsertChunk(anyString(), anyInt(), anyString(), anyString(), any(), anyString());
        // job is DONE = captioned (never re-caption); embedding is now the reconciler's job
        verify(jobRepo).markDone("job1");
    }

    @Test
    void embedSuccess_writesChunkWithVector() throws Exception {
        var jobRepo = mock(PendingImageJobRepository.class);
        var emb = mock(EmbeddingService.class);
        var chunkRepo = mock(NoteChunkRepository.class);
        var worker = worker(jobRepo, emb, chunkRepo);

        when(emb.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        var job = new PendingImageJob("notes/y.md", "img2.png");
        job.setId("job2");
        worker.handleResult(job, mapper.readTree("{\"text\":\"caption two\"}"), "mistral");

        verify(chunkRepo).upsertChunk(eq("notes/y.md"), eq(0), eq("image"), eq("caption two"), any(), anyString());
        verify(chunkRepo, never()).upsertChunkTextOnly(anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(jobRepo).markDone("job2");
    }
}
