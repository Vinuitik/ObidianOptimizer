package com.obsidian.obsidian.cards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The per-note tiered knapsack: a test must cover basic / mid / advanced (when
 * present) and never exceed the per-tier / per-note caps — that's what makes the
 * resulting score a representative band signal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentServiceTest {

    @Mock AssignmentRepository repo;
    @Mock ReviewService reviewService;

    AssignmentService service;
    static final String NOTE = "/vault/n.md";

    @BeforeEach
    void setUp() {
        service = new AssignmentService(repo, reviewService);
        when(repo.notesInScope(NOTE)).thenReturn(List.of(NOTE));
        when(repo.currentCycle(anyString(), anyString())).thenReturn(1);
        when(repo.insertAssignment(any(), anyInt(), anyInt(), any(), any()))
            .thenReturn(UUID.randomUUID());
    }

    private Map<String, Object> mcq(int difficulty) {
        Map<String, Object> c = new HashMap<>();
        c.put("id", UUID.randomUUID());
        c.put("note_path", NOTE);
        c.put("type", "mcq");
        c.put("difficulty", difficulty);
        c.put("payload", "{\"question\":\"Q\",\"options\":[\"a\",\"b\"],\"correct\":0}");
        return c;
    }

    /** Stub a tier's bag to hand out the given cards in order, then run dry (null). */
    private void tier(int min, int max, Map<String, Object>... cards) {
        var stub = when(repo.drawCardInTier(eq(NOTE), eq(min), eq(max), anyInt(), any()));
        for (Map<String, Object> c : cards) stub = stub.thenReturn(c);
        stub.thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cards(Map<String, Object> assignment) {
        return (List<Map<String, Object>>) assignment.get("cards");
    }

    @Test
    void coversEachTierOnce_whenOneCardPerTier() {
        tier(1, 2, mcq(1));
        tier(3, 3, mcq(3));
        tier(4, 5, mcq(5));

        var cards = cards(service.build(NOTE, 10));

        assertThat(cards).hasSize(3);
        assertThat(cards).extracting(c -> AssignmentService.Tier.of(((Number) c.get("difficulty")).intValue()))
            .containsExactlyInAnyOrder(AssignmentService.Tier.BASIC,
                AssignmentService.Tier.MID, AssignmentService.Tier.ADVANCED);
    }

    @Test
    void capsEachTierAtMaxPerTier() {
        // Five basic cards available, but the tier cap is MAX_PER_TIER.
        tier(1, 2, mcq(1), mcq(2), mcq(1), mcq(2), mcq(1));
        tier(3, 3);  // empty
        tier(4, 5);  // empty

        var cards = cards(service.build(NOTE, 10));

        assertThat(cards).hasSize(AssignmentService.MAX_PER_TIER);
    }

    @Test
    void neverExceedsPerNoteCap() {
        // Plenty in every tier; the note cap (not the tiers) is the binding limit.
        tier(1, 2, mcq(1), mcq(2), mcq(1));
        tier(3, 3, mcq(3), mcq(3), mcq(3));
        tier(4, 5, mcq(4), mcq(5), mcq(4));

        var cards = cards(service.build(NOTE, 100));

        assertThat(cards.size()).isLessThanOrEqualTo(AssignmentService.MAX_CARDS_PER_NOTE);
    }

    @Test
    void scoreWeightsAdvancedHigher_viaDifficultyAsPoints() {
        tier(1, 2, mcq(1));
        tier(3, 3, mcq(3));
        tier(4, 5, mcq(5));

        var assignment = service.build(NOTE, 10);

        int actual = (int) assignment.get("actualPoints");
        assertThat(actual).isEqualTo(1 + 3 + 5);   // points = difficulty
    }

    @Test
    void throwsWhenScopeHasNoCards() {
        when(repo.notesInScope("/vault/empty.md")).thenReturn(List.of());
        assertThatThrownBy(() -> service.build("/vault/empty.md", 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no active cards");
    }
}
