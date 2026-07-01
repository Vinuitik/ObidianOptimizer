package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.cards.CardGenerationService;
import com.obsidian.obsidian.cards.CardJobWorker;
import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the head-of-line-blocking bug: a worker whose drain BLOCKS
 * (image captioning / cards waiting on cooled LLM providers) must not stall the
 * other workers. Before the WorkerLane change every {@code @Scheduled} method ran
 * on one shared thread, so this test would hang/fail; with per-worker lanes the
 * embedder makes progress while the cards worker is stuck.
 */
class SchedulerIsolationTest {

    @Test
    void embeddingProgressesWhileCardsWorkerIsBlocked() throws Exception {
        // ── Cards worker: its drain blocks mid-generation (simulates a cooled LLM) ──
        CardRepository cardRepo = mock(CardRepository.class);
        CardGenerationService gen = mock(CardGenerationService.class);
        CountDownLatch cardsBlock   = new CountDownLatch(1);
        CountDownLatch cardsStarted = new CountDownLatch(1);
        when(cardRepo.findNotesNeedingCards(anyInt()))
            .thenReturn(List.of(Map.of("path", "/note.md", "body_hash", "h1")));
        when(gen.generateFor(any(), any())).thenAnswer(inv -> {
            cardsStarted.countDown();
            cardsBlock.await(5, TimeUnit.SECONDS);   // hold the cards lane hostage
            return null;
        });
        CardJobWorker cards = new CardJobWorker(cardRepo, gen);
        ReflectionTestUtils.setField(cards, "enabled", true);
        ReflectionTestUtils.setField(cards, "batchLimit", 10);

        // ── Embedding worker: fast, records progress via markEmbedded ──
        NoteIndexRepository idx = mock(NoteIndexRepository.class);
        EmbeddingService emb    = mock(EmbeddingService.class);
        NoteChunkRepository chunk = mock(NoteChunkRepository.class);
        CountDownLatch embedded = new CountDownLatch(1);
        when(idx.findNotesNeedingEmbedding(anyInt())).thenReturn(Map.of("/essay.md", "eh1"));
        when(emb.indexNote("/essay.md")).thenReturn(true);
        doAnswer(inv -> { embedded.countDown(); return null; })
            .when(idx).markEmbedded(eq("/essay.md"), eq("eh1"));
        NoteEmbeddingWorker embed = new NoteEmbeddingWorker(idx, emb, chunk);
        ReflectionTestUtils.setField(embed, "enabled", true);
        ReflectionTestUtils.setField(embed, "batchSize", 8);

        try {
            // Fire the cards tick → returns immediately, its drain blocks on the cards lane.
            cards.scanAndGenerate();
            assertThat(cardsStarted.await(2, TimeUnit.SECONDS))
                .as("cards drain should be running and blocked").isTrue();

            // Fire the embed tick → must finish embedding WHILE cards is still blocked.
            embed.embedPendingNotes();
            assertThat(embedded.await(2, TimeUnit.SECONDS))
                .as("embedding must progress while the cards worker is blocked").isTrue();

            // Prove the cards worker really was blocked the whole time.
            assertThat(cardsBlock.getCount()).isEqualTo(1);
        } finally {
            cardsBlock.countDown();
            embed.stopLane();
            ReflectionTestUtils.invokeMethod(cards, "stopLane");   // package-private lifecycle hook
        }
    }
}
