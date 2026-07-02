package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.ml.NoteChunkRepository.PendingChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The embed queue: NULL-vector chunks get embedded, and stay NULL (retryable) when
 * the embedder is unavailable. Covers both image- and text-source chunks since the
 * reconciler keys only on a missing vector.
 */
class ChunkEmbeddingReconcilerTest {

    private ChunkEmbeddingReconciler reconciler(NoteChunkRepository repo, EmbeddingService emb) {
        var r = new ChunkEmbeddingReconciler(repo, emb);
        ReflectionTestUtils.setField(r, "enabled", true);
        ReflectionTestUtils.setField(r, "batchSize", 100);
        return r;
    }

    @Test
    void fillsNullVectorChunkOnSuccessfulEmbed() {
        var repo = mock(NoteChunkRepository.class);
        var emb = mock(EmbeddingService.class);
        when(repo.findChunksNeedingEmbedding(anyInt()))
            .thenReturn(List.of(new PendingChunk("notes/x.md", "image", 0, "a caption")));
        when(emb.embed("a caption")).thenReturn(new float[]{0.3f, 0.4f});
        when(repo.setChunkEmbedding(eq("notes/x.md"), eq("image"), eq(0), any())).thenReturn(true);

        reconciler(repo, emb).drain();

        verify(repo).setChunkEmbedding(eq("notes/x.md"), eq("image"), eq(0), any());
    }

    @Test
    void leavesChunkNullWhenEmbedderUnavailable() {
        var repo = mock(NoteChunkRepository.class);
        var emb = mock(EmbeddingService.class);
        when(repo.findChunksNeedingEmbedding(anyInt()))
            .thenReturn(List.of(new PendingChunk("notes/x.md", "text", 2, "some prose")));
        when(emb.embed("some prose")).thenReturn(null);   // embedder down

        reconciler(repo, emb).drain();

        // vector never written → chunk stays NULL → retried next tick
        verify(repo, never()).setChunkEmbedding(any(), any(), anyInt(), any());
    }
}
