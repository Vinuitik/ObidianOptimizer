package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Open-answer two-pass judging: band routing, 1s budget → provisional → refine, complete() wait, card order. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentJudgeTest {

    static final ObjectMapper M = new ObjectMapper();

    @Mock AssignmentRepository repo;
    @Mock ReviewService reviewService;
    @Mock ReviewPreparationService reviewPrep;

    /** Stubs the embedder: cosine pass (llm=false) and full pass (llm=true) are scripted separately. */
    static class Svc extends AssignmentService {
        volatile String cosineJson;
        volatile String fullJson;
        volatile CountDownLatch fullGate;       // full judge blocks until released
        final List<Boolean> llmFlags = new ArrayList<>();
        Svc(AssignmentRepository r, ReviewService rs, ReviewPreparationService p) { super(r, rs, p); }
        @Override JsonNode postJson(String path, JsonNode body) {
            try {
                boolean llm = body.path("llm").asBoolean(true);
                synchronized (llmFlags) { llmFlags.add(llm); }
                if (llm) {
                    if (fullGate != null) fullGate.await(10, TimeUnit.SECONDS);
                    return fullJson == null ? null : M.readTree(fullJson);
                }
                return M.readTree(cosineJson);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    Svc svc;
    final UUID assignmentId = UUID.randomUUID();
    final UUID attemptId = UUID.randomUUID();
    Map<String, Object> card;

    @BeforeEach
    void setUp() {
        svc = new Svc(repo, reviewService, reviewPrep);
        card = new HashMap<>();
        card.put("id", UUID.randomUUID());
        card.put("type", "open");
        card.put("difficulty", 4);
        card.put("status", "ACTIVE");
        card.put("payload", "{\"question\":\"Q\",\"reference_answers\":[\"ref one\",\"ref two\"],\"key_points\":[\"k\"]}");
        when(repo.findCard(eq(1L), any())).thenReturn(card);
        when(repo.insertAttempt(any(), any(), any(), any(), anyBoolean(), anyInt(), any(), anyBoolean()))
            .thenReturn(attemptId);
        when(repo.findAssignment(eq(1L), any())).thenReturn(new HashMap<>());
        when(repo.perNoteScores(any())).thenReturn(List.of());
    }

    private Map<String, Object> submit() {
        return svc.submitAttempt(1L, assignmentId, (UUID) card.get("id"), "my answer");
    }

    // ── band routing ──────────────────────────────────────────────────────────

    @Test
    void clearBand_isFinalWithoutFullJudge() {
        svc.cosineJson = "{\"verdict\":\"CORRECT\",\"similarity\":0.95,\"judge_used\":false,\"feedback\":null}";
        var out = submit();
        assertThat(out.get("verdict")).isEqualTo("CORRECT");
        assertThat(out.get("provisional")).isEqualTo(false);
        assertThat(out.get("referenceAnswer")).isEqualTo("ref one");
        assertThat(out.get("pointsEarned")).isEqualTo(4);
        assertThat(svc.llmFlags).containsExactly(false);
        verify(repo).insertAttempt(any(), any(), any(), eq("CORRECT"), eq(false), eq(4), any(), eq(false));
    }

    @Test
    void midBand_fullJudgeWithinBudget_isFinalNotProvisional() {
        svc.cosineJson = "{\"verdict\":\"PARTIAL\",\"similarity\":0.6,\"judge_used\":false,\"feedback\":null}";
        svc.fullJson = "{\"verdict\":\"WRONG\",\"similarity\":0.6,\"judge_used\":true,\"feedback\":\"misses negation\"}";
        var out = submit();
        assertThat(out.get("verdict")).isEqualTo("WRONG");
        assertThat(out.get("provisional")).isEqualTo(false);
        assertThat(out.get("feedback")).isEqualTo("misses negation");
        assertThat(svc.llmFlags).containsExactly(false, true);
        verify(repo).insertAttempt(any(), any(), any(), eq("WRONG"), eq(true), eq(0), eq("misses negation"), eq(false));
    }

    // ── timeout → provisional → update ────────────────────────────────────────

    @Test
    void midBand_lateJudge_returnsProvisionalThenUpdatesAttempt() throws Exception {
        svc.judgeWaitMs = 150;
        svc.cosineJson = "{\"verdict\":\"PARTIAL\",\"similarity\":0.6,\"judge_used\":false,\"feedback\":null}";
        svc.fullJson = "{\"verdict\":\"CORRECT\",\"similarity\":0.6,\"judge_used\":true,\"feedback\":\"good\"}";
        svc.fullGate = new CountDownLatch(1);

        long t0 = System.nanoTime();
        var out = submit();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).isLessThan(1000);
        assertThat(out.get("provisional")).isEqualTo(true);
        assertThat(out.get("verdict")).isEqualTo("PARTIAL");
        assertThat(out.get("referenceAnswer")).isEqualTo("ref one");
        assertThat(out).containsKey("feedback");
        assertThat(out.get("feedback")).isNull();
        assertThat(out.get("pointsEarned")).isEqualTo(2);
        assertThat(out.get("maxPoints")).isEqualTo(4);
        verify(repo).insertAttempt(any(), any(), any(), eq("PARTIAL"), eq(false), eq(2), any(), eq(true));
        verify(repo, never()).refineAttempt(any(), any(), anyBoolean(), anyInt(), any());

        svc.fullGate.countDown();                       // judge lands late
        verify(repo, timeout(3000)).refineAttempt(attemptId, "CORRECT", true, 4, "good");
    }

    // ── complete() waits ──────────────────────────────────────────────────────

    @Test
    void complete_waitsForPendingRefinementBeforeScoring() throws Exception {
        svc.judgeWaitMs = 50;
        svc.cosineJson = "{\"verdict\":\"PARTIAL\",\"similarity\":0.6,\"judge_used\":false,\"feedback\":null}";
        svc.fullJson = "{\"verdict\":\"CORRECT\",\"similarity\":0.6,\"judge_used\":true,\"feedback\":null}";
        svc.fullGate = new CountDownLatch(1);
        submit();                                       // provisional

        AtomicBoolean refinedBeforeScores = new AtomicBoolean(false);
        doAnswer(inv -> { refinedBeforeScores.set(mockingDetails(repo).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("refineAttempt"))); return List.of(); })
            .when(repo).perNoteScores(any());

        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) {} svc.fullGate.countDown(); }).start();
        long t0 = System.nanoTime();
        svc.complete(1L, assignmentId);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(refinedBeforeScores).isTrue();
        assertThat(ms).isGreaterThanOrEqualTo(250).isLessThan(5000);
        assertThat(svc.pendingRefinements).isEmpty();
    }

    @Test
    void complete_givesUpAfterBudget_andGradesWithProvisional() {
        svc.judgeWaitMs = 50;
        svc.judgeCompleteWaitMs = 200;
        svc.cosineJson = "{\"verdict\":\"PARTIAL\",\"similarity\":0.6,\"judge_used\":false,\"feedback\":null}";
        svc.fullJson = "{\"verdict\":\"CORRECT\",\"similarity\":0.6,\"judge_used\":true,\"feedback\":null}";
        svc.fullGate = new CountDownLatch(1);           // never released within the test window
        submit();

        long t0 = System.nanoTime();
        svc.complete(1L, assignmentId);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).isBetween(180L, 2000L);
        verify(repo).perNoteScores(assignmentId);
        verify(repo, never()).refineAttempt(any(), any(), anyBoolean(), anyInt(), any());
        svc.fullGate.countDown();
    }

    @Test
    void complete_withNothingPending_doesNotWait() {
        long t0 = System.nanoTime();
        svc.complete(1L, assignmentId);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(200);
    }

    // ── card order ────────────────────────────────────────────────────────────

    private static Map<String, Object> c(String type, int diff, String tag) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", UUID.randomUUID());
        m.put("type", type);
        m.put("difficulty", diff);
        m.put("tag", tag);
        return m;
    }

    private static List<String> tags(List<Map<String, Object>> l) {
        return l.stream().map(x -> (String) x.get("tag")).toList();
    }

    @Test
    void order_alternatesHardEasy_openFirstInHardSlots() {
        var cards = List.of(c("mcq", 1, "e1"), c("mcq", 4, "h4"), c("open", 2, "o2"),
            c("mcq", 2, "e2"), c("open", 5, "o5"), c("mcq", 3, "h3"));
        var out = tags(AssignmentService.orderCards(cards));
        // hard pool = o2,o5 (open first), h3, h4 ; easy = e1,e2 → H E H E then leftover hard by difficulty
        assertThat(out).containsExactly("o2", "e1", "o5", "e2", "h3", "h4");
    }

    @Test
    void order_leftoverAppendedByDifficulty_whenOnePoolRunsOut() {
        var cards = List.of(c("mcq", 5, "h5"), c("mcq", 1, "e1"), c("mcq", 2, "e2"),
            c("mcq", 2, "e2b"), c("mcq", 1, "e1b"));
        var out = tags(AssignmentService.orderCards(cards));
        assertThat(out.subList(0, 2)).containsExactly("h5", "e1");
        assertThat(out.subList(2, 5)).containsExactlyInAnyOrder("e1b", "e2", "e2b");
        assertThat(out).hasSize(5);
    }

    @Test
    void order_keepsAllCards() {
        var cards = List.of(c("mcq", 1, "a"), c("open", 1, "b"), c("exercise", 3, "c"));
        assertThat(AssignmentService.orderCards(cards)).hasSize(3);
    }
}
