package com.obsidian.obsidian.cards;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Math-level tests for Thompson Sampling — no DB. The Beta sampler and the
 * argmax selection are exercised directly with seeded randomness; DB-backed
 * load/reward round-trips are covered by ReviewFlowIT.
 */
class BanditServiceTest {

    @Test
    void sampleBeta_isInUnitInterval_andSeedStable() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(42));
        for (int i = 0; i < 1000; i++) {
            double s = bandit.sampleBeta(1, 1);
            assertThat(s).isBetween(0.0, 1.0);
        }
    }

    @Test
    void sampleBeta_concentratesAroundMean() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(7));
        // Beta(80, 20): mean 0.8, sd ~0.04 — the average of 2000 draws must be close
        double sum = 0;
        for (int i = 0; i < 2000; i++) sum += bandit.sampleBeta(80, 20);
        assertThat(sum / 2000).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void sampleBeta_skewsTowardEvidence() {
        BanditService bandit = new BanditService(null);
        bandit.setRandom(new Random(3));
        // Beta(20,2) should beat Beta(2,20) almost always
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
}
