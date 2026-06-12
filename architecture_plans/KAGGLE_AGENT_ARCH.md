# Kaggle Optimizer Agent — Architecture Plan

[NOT IMPLEMENTED] — separate project (new repo `KaggleOptimizer`), Python-only, no Spring. Planned layout: `agent/loop.py`, `agent/planner.py`, `harness/executor.py`, `harness/ledger.py`, `harness/guards.py`, `techniques/` (typiclust.py, flexmatch.py, bayes_borrow.py, gbdt.py, nn.py, ensemble.py), `data/profile.py`, `evalbench/` (comps.yaml, score.py, report.py)

Goal: input a Kaggle competition/dataset slug → agent trains models using advanced techniques → produces a submission. Eval: run it on completed competitions and compute where the submission *would have ranked* on the real leaderboard.

Prior art to steal from (don't reinvent): **MLE-bench** (OpenAI — exactly this eval: agents on 75 Kaggle comps, medal-percentile scoring) and **AIDE** (tree search over solution drafts: draft → improve → debug nodes, best CV wins). Our twist is the technique library — TypiClust/FlexMatch/Bayesian borrowing as first-class harness tools, because the point is applying YOUR ML coursework, not autogluon-in-a-trenchcoat.

---

## Core shape: agent loop, but the harness owns everything that can be owned

Unlike ingest/flashcards (fixed pipelines), model development has an unknown path — this is the legitimate use case for an agent loop. The discipline moves into the harness:

```
slug → ACQUIRE (det.) → PROFILE (det.) → PLAN (LLM, 1 call, schema)
    → EXPERIMENT LOOP (agent proposes JSON specs; harness executes, guards, logs)
    → ENSEMBLE/SELECT (det. from ledger, CV-score ordered)
    → SUBMIT (det.) → EVAL REPORT (det., leaderboard percentile)
```

The agent **never writes free-form training code in v1**. It composes pre-written, parameterized building blocks (the technique library) via JSON experiment specs. Code-gen escape hatch is a v2 decision — AIDE-style code drafting measurably helps, but costs sandboxing + debugging-loop complexity. [NOT IMPLEMENTED]

---

## Stage 1 — ACQUIRE (deterministic)

```
kaggle API: competition files / dataset download → cache dir keyed by slug
  → parse competition meta: metric (from comp page API), submission format
    (sample_submission.csv schema), train/test file inventory
  → emit AcquireManifest JSON
```
Auth: `KAGGLE_USERNAME`/`KAGGLE_KEY` env vars (kaggle CLI standard).

## Stage 2 — PROFILE (deterministic)

The "extraction bundle" of this project — the LLM plans from this JSON, never from raw CSVs:

```
per file/column: dtype, cardinality, missing %, examples (k=5), target dist,
  datetime presence (→ time-split suspicion), group-id candidates (high-card
  cols repeated across rows), text/image column detection
leakage scans: train/test duplicate rows; single features with suspicious
  target MI; future-dated timestamps vs test
CV design hints: time column found → TimeSeriesSplit; group col → GroupKFold;
  else StratifiedKFold(5)
unlabeled-data detection: extra files without target → FlexMatch candidate
size report vs hardware budget (GTX 1650 4GB / CPU)
```

## Stage 3 — PLAN (LLM, one schema-validated call)

Input: profile JSON + metric. Output `Plan` JSON: task type, chosen CV scheme (must pick from harness-supported schemes), candidate model families ranked, applicable techniques with *why* (see applicability matrix below), budget allocation (experiments per family), success criterion. Max 2 retries on schema failure — same rule as the other agents.

### Technique applicability matrix (encoded as harness hints, LLM confirms)

| Technique | Fires when | Library impl |
|---|---|---|
| GBDT suite (LGBM/XGB/CatBoost) | tabular, always first | `techniques/gbdt.py` |
| CV-safe target encoding | high-card categoricals | inside gbdt pipeline |
| FlexMatch | unlabeled pool exists (extra data or self-created by masking) | `techniques/flexmatch.py` |
| TypiClust | label budget scenario / coreset selection for expensive NN training | `techniques/typiclust.py` |
| Bayesian borrowing (hierarchical partial pooling) | grouped data, many small groups, group-level targets | `techniques/bayes_borrow.py` |
| NN fine-tune (timm / HF) | image/text columns; 4GB → small backbones, frozen-then-unfreeze | `techniques/nn.py` |
| Stacking/blending | ≥3 diverse ledger entries above baseline | `techniques/ensemble.py` |

## Stage 4 — EXPERIMENT LOOP (the agent, in a cage)

```
loop (max EXPERIMENT_BUDGET=24 specs, max WALL_CLOCK=12h, whichever first):
  agent sees: plan + profile + LEDGER (all past specs + CV scores + durations
              + failure reasons) — that ledger IS its memory, full stop
  agent emits: ExperimentSpec JSON
    { family, features: [transform names], params: {...},
      techniques: {flexmatch: {...}} , cv: inherited, seed }
  harness GUARDS (reject before run, error returned to agent as feedback):
    - spec schema + param ranges
    - CV scheme immutable after plan (no split-shopping)
    - no transform that touches test-fold target (whitelist of safe transforms)
    - duplicate-spec hash check (don't re-run what's in the ledger)
    - estimated VRAM/time vs remaining budget
  harness EXECUTES: deterministic runner, fixed seeds, OOF predictions saved
  harness LOGS: ledger row (spec hash, CV mean±std, per-fold, time, artifacts)
  early stop: PATIENCE=6 specs without CV improvement → force ensemble stage
```

**Test-set discipline (the most important guard):** the agent only ever sees CV scores. OOF predictions feed stacking; test predictions are generated once, at the end, from selected models. In eval mode there is no leaderboard feedback loop — one submission, k=1 (report best-of-k separately and honestly if measuring pass@k).

## Stage 5 — ENSEMBLE & SELECT (deterministic)

Top-K ledger entries by CV → blend search on OOF (hill-climbing weights) and one stack (logistic/ridge meta-learner on OOF). Final pick = best CV among {best single, blend, stack}. The LLM does not choose here — CV does.

## Stage 6 — SUBMIT & EVAL

```
dev mode:  kaggle competitions submit (late submission) → real private score
eval mode (the benchmark):
  evalbench/comps.yaml: list of completed comps (curated, see contamination note)
  → run agent end-to-end, frozen config, no web access
  → late-submit via API → private LB score
  → percentile = rank of score within Meta Kaggle final leaderboard for that comp
  → report: percentile, medal-equivalent (MLE-bench convention), cost, wall time
```

Comp curation rules: late submission enabled; leaderboard in Meta Kaggle; prefer post-LLM-cutoff or obscure comps (contamination — see Technology Notes); mix tabular/image/text; small data sizes (hardware).

---

## Hardware reality (GTX 1650, 4GB)

- Tabular/GBDT: CPU-bound, totally fine — this is most of Kaggle anyway.
- NN: small timm backbones (efficientnet-b0..b2, convnext-tiny) at 224px, batch 16–32 with AMP; gradient accumulation; frozen-backbone first epoch. No LLM fine-tuning; text → HF distil-class models or GBDT-on-embeddings (reuse mxbai pattern).
- FlexMatch doubles batch cost (labeled+unlabeled streams) → halve batch, accumulate.
- Overnight-friendly by design: the loop checkpoints after every experiment; resume = re-read ledger.

---

## Technology Notes

- **Why no free-form code-gen in v1**: AIDE-style agents spend most failures in their own debugging loops. A parameterized technique library makes every experiment runnable-by-construction, so the agent's intelligence goes into *what to try*, not *how to not crash*. The trade: less ceiling. v2 escape hatch = one `custom_transform` slot, sandboxed like the flashcards code-runner (FLASHCARDS_ARCH).
- **Ledger as agent memory**: append-only sqlite (`ledger.db`). The prompt each iteration = plan + profile + ledger table rendered compactly. No conversation history is carried — the loop is stateless given the ledger, which makes it resumable, debuggable, and cache-friendly.
- **Contamination is the eval's biggest threat**: LLMs have memorized winning solutions to famous comps (Titanic effect) and MLE-bench documents this. Mitigations: comps after model cutoff or low-traffic comps; no web tools in eval mode; report comp list with the results so the eval is auditable.
- **Late submissions**: most completed comps accept them and score on the true private set — that's ground-truth eval for free. Some comps disable it; comps.yaml curation must check. Kernel-only comps can't be late-submitted via API — exclude.
- **Meta Kaggle** (kaggle.com/datasets/kaggle/meta-kaggle): public dump incl. Teams + Submissions with private scores — gives full final leaderboards for percentile computation without scraping HTML.
- **Seeds and CV variance**: GBDT CV std on small comps often exceeds real differences between specs. Guard: improvements < 0.3×CV-std are recorded as "noise-level" in the ledger so the agent doesn't chase noise (and PATIENCE counts them as no-improvement).
- **Bayesian borrowing scope**: v1 = hierarchical shrinkage via PyMC or analytic empirical-Bayes for group means feeding features into GBDT — not full Bayesian NN. It's a feature-engineering-grade tool here.
- **Planner model**: same economics as the other agents — Haiku for loop iterations (cheap, many calls), allow `PLANNER_MODEL=sonnet` for the one-shot Stage 3 plan where quality compounds. LLM calls follow the household pattern locked in FLASHCARDS_ARCH: **host-wrapper `POST /complete` → claude CLI** (subscription credits), not the Anthropic API.

---

## Change Index

| Thing to change | Where (planned) |
|---|---|
| Experiment / time budgets | `agent/loop.py → EXPERIMENT_BUDGET / WALL_CLOCK` |
| Patience | `agent/loop.py → PATIENCE` |
| Loop / planner models | `LOOP_MODEL` / `PLANNER_MODEL` env vars |
| Allowed transforms (leak whitelist) | `harness/guards.py → SAFE_TRANSFORMS` |
| CV schemes | `harness/executor.py → CV_SCHEMES` |
| Technique library | `techniques/*.py` — one file per technique |
| Eval comp list | `evalbench/comps.yaml` |
| Noise-level threshold | `harness/ledger.py → NOISE_FRACTION` (0.3) |
| Kaggle auth | `KAGGLE_USERNAME` / `KAGGLE_KEY` env vars |
