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
 * One Beta(α,β) per (context bucket, arm). Reward is delayed — it arrives at
 * the NEXT review of the same note: recalled (Good or better) → α+1, else β+1.
 * Priors α=β=1 (uniform). No weights, no neural nets — just Beta updates.
 *
 * v1 context = FSRS difficulty band × stability band (9 buckets × 5 arms).
 * The Trello card also lists historical recall rate as context — deferred
 * until attempt history accumulates; with 45 Beta cells data stays dense.
 */
@Service
public class BanditService {

    public static final double[] ARMS = {0.7, 0.85, 1.0, 1.2, 1.5};

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

    /** Delayed reward from the next review: recalled → α+1, forgotten → β+1. */
    public void reward(String bucket, double arm, boolean recalled) {
        jdbc.update("""
            INSERT INTO bandit_arms(context_bucket, arm, alpha, beta)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (context_bucket, arm) DO UPDATE SET
              alpha = bandit_arms.alpha + EXCLUDED.alpha - 1,
              beta  = bandit_arms.beta  + EXCLUDED.beta  - 1
            """, bucket, arm, recalled ? 2.0 : 1.0, recalled ? 1.0 : 2.0);
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
