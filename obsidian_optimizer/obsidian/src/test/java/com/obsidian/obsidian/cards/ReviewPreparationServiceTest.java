package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.ml.EmbeddingService;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * On-demand review prep: normalize → (cards + embed if missing), each writing the
 * DB state that makes the background job skip the note (card attempt keyed on
 * body_hash).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewPreparationServiceTest {

    @Mock FsrsStateWriter stateWriter;
    @Mock CardRepository cardRepo;
    @Mock CardGenerationService generationService;
    @Mock NoteIndexRepository noteIndex;
    @Mock EmbeddingService embeddingService;

    ReviewPreparationService prep;
    static final String NOTE = "/vault/n.md";

    @BeforeEach
    void setUp() {
        prep = new ReviewPreparationService(stateWriter, cardRepo, generationService,
            noteIndex, embeddingService);
    }

    @Test
    void alwaysNormalizesFsrsState() {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of(Map.of("id", "x")));  // already has cards
        prep.prepare(1L, NOTE);
        verify(stateWriter).normalizeLegacy(1L, NOTE);
    }

    @Test
    void whenNoCards_andReady_generatesAgainstBodyHash_andEmbeds() throws Exception {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of());        // no cards yet
        when(noteIndex.getBodyHash(1L, NOTE)).thenReturn("bodyhash123");
        when(cardRepo.isReadyForCards(1L, NOTE)).thenReturn(true);             // ingest + images done
        when(generationService.generateFor(1L, NOTE, "bodyhash123"))
            .thenReturn(new ObjectMapper().createObjectNode().put("stored", 4));

        prep.prepare(1L, NOTE);

        verify(generationService).generateFor(1L, NOTE, "bodyhash123");
        // recordAttempt MUST use body_hash so CardJobWorker.findNotesNeedingCards skips it
        verify(cardRepo).recordAttempt(1L, NOTE, "bodyhash123");
        verify(embeddingService).indexNote(1L, NOTE);
    }

    @Test
    void whenNotReady_defersCardGeneration_butStillEmbeds() {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of());
        when(noteIndex.getBodyHash(1L, NOTE)).thenReturn("bh");
        when(cardRepo.isReadyForCards(1L, NOTE)).thenReturn(false);   // ingest/images still pending

        prep.prepare(1L, NOTE);

        // No image-blind JIT cards — the background worker handles it once ready.
        verify(generationService, never()).generateFor(org.mockito.ArgumentMatchers.eq(1L), any(), any());
        verify(cardRepo, never()).recordAttempt(org.mockito.ArgumentMatchers.eq(1L), any(), any());
        // Embedding is independent of card readiness and still runs.
        verify(embeddingService).indexNote(1L, NOTE);
    }

    @Test
    void whenCardsExist_skipsGenerationAndEmbed() {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of(Map.of("id", "x")));
        prep.prepare(1L, NOTE);
        verify(generationService, never()).generateFor(org.mockito.ArgumentMatchers.eq(1L), any(), any());
        verify(embeddingService, never()).indexNote(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void whenGenerationYieldsNothing_doesNotRecordAttempt() {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of());
        when(noteIndex.getBodyHash(1L, NOTE)).thenReturn("bh");
        when(cardRepo.isReadyForCards(1L, NOTE)).thenReturn(true);
        when(generationService.generateFor(1L, NOTE, "bh")).thenReturn(null);   // embedder down / zero yield

        prep.prepare(1L, NOTE);

        verify(cardRepo, never()).recordAttempt(org.mockito.ArgumentMatchers.eq(1L), any(), any());  // don't burn the retry on a no-op
    }

    @Test
    void whenNotIndexed_skipsGeneration() {
        when(cardRepo.findActiveByNote(1L, NOTE)).thenReturn(List.of());
        when(noteIndex.getBodyHash(1L, NOTE)).thenReturn(null);    // not in the index yet

        prep.prepare(1L, NOTE);

        verify(generationService, never()).generateFor(org.mockito.ArgumentMatchers.eq(1L), any(), any());
    }
}
