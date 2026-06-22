package com.obsidian.obsidian.cards;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Thompson Sampling over interval multipliers (Option A — DECIDED):
 * FSRS computes the base interval; the sampled arm multiplies the SCHEDULED
 * date only. FSRS stability/difficulty state is never touched by the bandit.
 *
 * One Beta(α,β) per (context bucket, arm). Priors α=β=1 (uniform). v1 context =
 * FSRS difficulty band × stability band (9 buckets × 5 arms = 45 dense cells).
 *
 * Three properties make this absorb our non-pure-FSRS system (spread check,
 * bankruptcy, late/early reviews) instead of being confused by it:
 *
 *  1. INTERVAL-WEIGHTED REWARD (not binary recall). A recalled review pays the
 *     arm a reward scaled by how FAR it stretched: r = arm / MAX_ARM; a forget
 *     pays 0. argmax over arms of E[recall|arm]·arm lands at the forgetting-curve
 *     KNEE, not at the shortest arm — this is what kills the old "always 0.7" bias
 *     (compressing intervals trivially maximised raw recall).
 *
 *  2. EFFECTIVE-ARM ATTRIBUTION (done by the caller, ReviewService). The reward
 *     is credited to the arm matching the interval the note was ACTUALLY reviewed
 *     at, not the one we intended. Spread shifts + procrastination become free
 *     exploration data rather than contamination. {@link #reward} snaps the raw
 *     realised multiplier to the grid, so a huge procrastination can never credit
 *     an arm above the ceiling (MAX_ARM) — that snap-to-cap is the ratchet clamp.
 *
 *  3. DISCOUNTED BETA (non-stationary). Each update first relaxes the cell toward
 *     the (1,1) prior by {@link #DISCOUNT}, capping effective memory at ~1/(1-γ)
 *     and letting stale buckets re-open to exploration as habits/memory drift.
 *
 * Bankruptcy/chronic-neglect lapses are deliberately NOT fed here — those are
 * exogenous (the user didn't open the app), not a memory signal.
 *
 * Future knobs if overshoot is observed: raise the grid ceiling, or add
 * feedforward compensation for the systematic late-review offset (subtract the
 * average effective-minus-intended gap at choose time). Not built — scope.
 */
@Service
public class BanditService {

    /** Asymmetric grid: no sub-0.85 compression, expansion room up to a hard 2.0 ceiling. */
    public static final double[] ARMS = {0.85, 1.0, 1.25, 1.5, 2.0};

    /** Reward normaliser and attribution ceiling — the largest arm. */
    public static final double MAX_ARM = 2.0;

    /** Beta discount per update (relax toward prior). Effective memory ≈ 1/(1-γ) ≈ 33 obs. */
    static final double DISCOUNT = 0.97;

    private final JdbcTemplate jdbc;
    private Random random = new Random();

    public BanditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS bandit_arms (
              context_bucket TEXT NOT NULL,
              arm            REAL NOT NULL,
              alpha          REAL NOT NULL DEFAULT 1,
              beta           REAL NOT NULL DEFAULT 1,
              PRIMARY KEY (context_bucket, arm)
            )
            """);
    }

    /** Difficulty terciles × stability bands — keep coarse so Betas stay dense. */
    public String bucket(double difficulty, double stability) {
        String d = difficulty < 4 ? "dEasy" : difficulty <= 7 ? "dMid" : "dHard";
        String s = stability < 7 ? "sShort" : stability <= 30 ? "sMid" : "sLong";
        return d + ":" + s;
    }

    /** Sample every arm's Beta for this bucket, return the argmax multiplier. */
    public double chooseArm(String bucket) {
        Map<Double, double[]> params = loadParams(bucket);
        double bestArm = 1.0, bestSample = -1;
        for (double arm : ARMS) {
            double[] ab = params.getOrDefault(arm, new double[]{1, 1});
            double sample = sampleBeta(ab[0], ab[1]);
            if (sample > bestSample) {
                bestSample = sample;
                bestArm = arm;
            }
        }
        return bestArm;
    }

    /** Snap a raw realised multiplier to the nearest arm; clamps above MAX_ARM. */
    public double snapArm(double rawMultiplier) {
        double best = ARMS[0];
        double bestDist = Math.abs(rawMultiplier - best);
        for (double arm : ARMS) {
            double dist = Math.abs(rawMultiplier - arm);
            if (dist < bestDist) { bestDist = dist; best = arm; }
        }
        return best;
    }

    /**
     * Delayed reward from the next review, credited to the EFFECTIVE arm.
     *
     * @param rawEffectiveMultiplier  actualElapsed / baseFsrsInterval — the interval
     *                                the note was really reviewed at (snapped to grid).
     * @param recalled                Good or better (Hard = not recalled).
     *
     * Reward = recalled ? snappedArm / MAX_ARM : 0  (interval-weighted: stretching
     * further is worth more). Update is discounted toward the (1,1) prior first.
     */
    public void reward(String bucket, double rawEffectiveMultiplier, boolean recalled) {
        double arm = snapArm(rawEffectiveMultiplier);
        double r = recalled ? arm / MAX_ARM : 0.0;

        double[] ab = loadParams(bucket).getOrDefault(arm, new double[]{1, 1});
        // Relax toward the uniform prior, then add the fractional reward.
        double alpha = 1 + DISCOUNT * (ab[0] - 1) + r;
        double beta  = 1 + DISCOUNT * (ab[1] - 1) + (1 - r);

        jdbc.update("""
            INSERT INTO bandit_arms(context_bucket, arm, alpha, beta)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (context_bucket, arm) DO UPDATE SET
              alpha = EXCLUDED.alpha, beta = EXCLUDED.beta
            """, bucket, arm, alpha, beta);
    }

    private Map<Double, double[]> loadParams(String bucket) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT arm, alpha, beta FROM bandit_arms WHERE context_bucket = ?", bucket);
        Map<Double, double[]> params = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            params.put(((Number) row.get("arm")).doubleValue(),
                new double[]{((Number) row.get("alpha")).doubleValue(),
                             ((Number) row.get("beta")).doubleValue()});
        }
        return params;
    }

    // ── Beta sampling via two Gammas (Marsaglia–Tsang), no extra dependency ──

    double sampleBeta(double alpha, double beta) {
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        return x / (x + y);
    }

    private double sampleGamma(double shape) {
        if (shape < 1) {
            // Johnk boost: Gamma(a) = Gamma(a+1) * U^(1/a)
            return sampleGamma(shape + 1) * Math.pow(random.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = random.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0) continue;
            v = v * v * v;
            double u = random.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }

    /** Test hook — deterministic sampling. */
    void setRandom(Random random) {
        this.random = random;
    }
}
