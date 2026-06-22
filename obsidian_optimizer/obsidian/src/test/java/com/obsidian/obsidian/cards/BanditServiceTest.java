package com.obsidian.obsidian.cards;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Math-level tests for Thompson Sampling — the Beta sampler, arm snapping, and the
 * interval-weighted discounted reward. DB-backed load/choose round-trips are
 * covered by ReviewFlowIT.
 */
class BanditServiceTest {

    // ── Beta sampler ─────────────────────────────────────────────────────────

    @Test
    void sampleBeta_isInUnitInterval_andSeedStable() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(42));
        for (int i = 0; i < 1000; i++) {
            assertThat(bandit.sampleBeta(1, 1)).isBetween(0.0, 1.0);
        }
    }

    @Test
    void sampleBeta_concentratesAroundMean() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(7));
        double sum = 0;
        for (int i = 0; i < 2000; i++) sum += bandit.sampleBeta(80, 20);
        assertThat(sum / 2000).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void sampleBeta_skewsTowardEvidence() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(3));
        int wins = 0;
        for (int i = 0; i < 500; i++) {
            if (bandit.sampleBeta(20, 2) > bandit.sampleBeta(2, 20)) wins++;
        }
        assertThat(wins).isGreaterThan(490);
    }

    @Test
    void bucket_partitionsDifficultyAndStability() {
        BanditService bandit = new BanditService(null);
        assertThat(bandit.bucket(2.0, 3.0)).isEqualTo("dEasy:sShort");
        assertThat(bandit.bucket(5.0, 15.0)).isEqualTo("dMid:sMid");
        assertThat(bandit.bucket(9.0, 100.0)).isEqualTo("dHard:sLong");
    }

    // ── Arm snapping (effective-arm attribution + ratchet clamp) ─────────────

    @Test
    void snapArm_picksNearestGridArm() {
        BanditService bandit = new BanditService(null);
        assertThat(bandit.snapArm(1.05)).isEqualTo(1.0);
        assertThat(bandit.snapArm(1.30)).isEqualTo(1.25);
        assertThat(bandit.snapArm(1.40)).isEqualTo(1.5);
    }

    @Test
    void snapArm_clampsHugeProcrastinationToCeiling() {
        BanditService bandit = new BanditService(null);
        // A 5x-late review cannot teach an arm above the 2.0 ceiling — the clamp.
        assertThat(bandit.snapArm(5.0)).isEqualTo(2.0);
    }

    @Test
    void snapArm_clampsVeryEarlyReviewToFloor() {
        BanditService bandit = new BanditService(null);
        assertThat(bandit.snapArm(0.2)).isEqualTo(0.85);
    }

    // ── Interval-weighted discounted reward ──────────────────────────────────

    @Test
    void reward_recalled_isIntervalWeighted_andDiscountsTowardPrior() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Existing cell with accumulated evidence.
        when(jdbc.queryForList(anyString(), eq("dMid:sMid")))
            .thenReturn(List.of(Map.of("arm", 2.0, "alpha", 5.0, "beta", 2.0)));
        double[] ab = hookUpsert(jdbc);
        BanditService bandit = new BanditService(jdbc);

        // Recalled at effective 2.0 → r = 2.0/2.0 = 1.0.
        // alpha = 1 + 0.97*(5-1) + 1   = 5.88 ; beta = 1 + 0.97*(2-1) + 0 = 1.97
        bandit.reward("dMid:sMid", 2.0, true);

        assertThat(ab[0]).isCloseTo(5.88, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ab[1]).isCloseTo(1.97, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void reward_shortArm_paysLessThanLongArm_forSameRecall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());  // fresh prior {1,1}
        double[] ab = hookUpsert(jdbc);
        BanditService bandit = new BanditService(jdbc);

        bandit.reward("dEasy:sShort", 0.85, true);   // r = 0.85/2.0 = 0.425
        double shortAlpha = ab[0];
        bandit.reward("dEasy:sShort", 1.5, true);    // r = 1.5/2.0 = 0.75
        double longAlpha = ab[0];

        // Stretching further is worth more α — this is the anti-0.7-bias mechanism.
        assertThat(longAlpha).isGreaterThan(shortAlpha);
    }

    @Test
    void reward_forgotten_paysZero_allMassToBeta() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());  // prior {1,1}
        double[] ab = hookUpsert(jdbc);
        BanditService bandit = new BanditService(jdbc);

        bandit.reward("dHard:sLong", 1.5, false);    // r = 0
        // alpha = 1 + 0.97*0 + 0 = 1.0 ; beta = 1 + 0.97*0 + 1 = 2.0
        assertThat(ab[0]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ab[1]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * Records the (alpha, beta) of each upsert into the returned array. Uses an
     * Answer + getArgument (flattens varargs reliably, unlike ArgumentCaptor on
     * a multi-vararg call). Args: [0]=sql [1]=bucket [2]=arm [3]=alpha [4]=beta.
     */
    private static double[] hookUpsert(JdbcTemplate jdbc) {
        double[] ab = new double[2];
        doAnswer(inv -> {
            ab[0] = (double) inv.getArgument(3);   // alpha
            ab[1] = (double) inv.getArgument(4);   // beta
            return 1;
        }).when(jdbc).update(anyString(), any(), any(), any(), any());
        return ab;
    }
}
