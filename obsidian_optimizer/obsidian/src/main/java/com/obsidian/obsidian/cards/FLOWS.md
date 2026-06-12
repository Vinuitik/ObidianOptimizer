# Cards Domain Flows

Files: CardRepository.java, CardGenerationService.java, CardJobWorker.java, CardController.java, FsrsService.java, BanditService.java, ReviewService.java, ReviewController.java, NoteReviewRepository.java
Python agent: embedder/flashcards/generate.py, validate.py, solver_sandbox.py
Architecture: architecture_plans/FLASHCARDS_ARCH.md

---

## Review Flow (FSRS + bandit)

```
POST /api/reviews/grade {notePath, band}     band ∈ HARD/GOOD/EASY/VERY_EASY
  → ReviewService.grade():
    1. delayed bandit reward: previous pending (bucket, arm) on the note_reviews
       row gets α+1 if band ≥ GOOD else β+1   (BanditService.reward)
    2. pure FSRS-6 update (FsrsService — recall path only, NO Again/lapse grade;
       VERY_EASY maps to FSRS Easy, the distinction exists for band labels/rewards)
    3. bandit (Thompson Sampling, Beta per bucket×arm) samples arm
       m ∈ {0.7, 0.85, 1.0, 1.2, 1.5}; due = now + round(fsrsInterval × m)
       — Option A: the arm scales the SCHEDULED DATE only, never stored S/D
  → upsert note_reviews (stability, difficulty, due, pending_bucket, pending_arm)

GET /api/reviews/due?limit=50 → notes due now, most overdue first
```

Both UI modes converge here: slideshow posts the pressed button's band;
flashcards mode computes the band from the assignment score via
`ReviewService.Band.fromScore()` (GRADE_BANDS 40/70/90).

Context buckets: difficulty {<4, 4-7, >7} × stability {<7d, 7-30d, >30d} —
9 buckets × 5 arms. Historical recall rate as context: deferred until attempt
history accumulates.

FsrsService is pinned against py-fsrs outputs (FsrsServiceTest); reference
values regenerate via `embedder/_fsrs_reference.py`. Subtlety: difficulty
mean-reversion uses the UNCLAMPED Easy initial difficulty (negative with
default weights) — matching py-fsrs exactly.

---

## Generation Flow

```
CardJobWorker @Scheduled (default every 30min, 2min after startup)
  → CardRepository.findNotesNeedingCards(batchLimit)
      SQL diff: notes WHERE sr_due IS NOT NULL
        AND no ACTIVE cards with source_hash == notes.content_hash
        AND no card_gen_attempts row for that (path, hash)
  → per note: recordAttempt(path, hash) FIRST (zero-card notes must not retry
      every cycle — CLI credits), then CardGenerationService.generateFor()
  → POST embedder /flashcards/generate {note_path, source_hash}
      embedder reads the note from its read-only /vault mount
      → flashcards/generate.py:
          PASS 1: GEN_PROMPT → host-wrapper POST /complete (claude CLI,
                  subscription credits — NEVER the Anthropic API)
          PASS 2: blind self-check — model re-answers its own mcq/exercise
                  questions without seeing answers; mismatches dropped
          validate.py: schema + solver sandbox checks; failures re-prompted,
                  MAX_RETRIES=2, survivors stored
      → upsert into cards (note_path + card_hash dedupe); older-source cards
        ARCHIVED (attempt history preserved, never deleted)
```

No queue table (deviation from FLASHCARDS_ARCH's pending_card_jobs): the
notes.content_hash ↔ cards.source_hash diff IS the work list — covers app
edits, sync downloads, chrono rewrites, and external Obsidian edits with zero
call-site hooks. The attempt ledger (card_gen_attempts) bounds retries.

## REST Endpoints (session auth)

| Method | Path | Does |
|---|---|---|
| GET | `/api/cards?notePath=` | ACTIVE cards for a note |
| GET | `/api/cards/stats` | active/archived/notes-with-cards counts |
| POST | `/api/cards/generate {notePath}` | force generation now, synchronous |

## Card types (payload JSONB)

`mcq` (options + correct index) · `open` (≥2 reference_answers, key_points) ·
`exercise` (template + param domains + sandboxed solver + named conditions).
`code` cards: [NOT IMPLEMENTED] — needs the code-runner container (see ARCH).

## Technology Notes

- **Solver sandbox**: AST whitelist (math/itertools + safe builtins, no imports,
  no attribute access off non-modules, denied-name list) + subprocess with 2s
  timeout and 128MB rlimit (Linux only — Windows test runs skip the rlimit).
  Containment level: blocks accidents and casual escapes, not determined attackers.
- **Eligibility gate**: only notes with `sr_due` set get cards. A vault note
  without spaced-rep frontmatter is invisible to the worker.
- **Batch cap** (`cards.batch-limit`, default 10/pass): a fresh vault generates
  gradually across cycles instead of burning a night of credits at once.
- **Schema dual-ownership**: cards DDL lives in both CardRepository.initSchema
  (Java) and generate.py ensure_schema (Python), both CREATE IF NOT EXISTS —
  keep them in sync when altering.

## Change Index

| Thing to change | Where |
|---|---|
| Enable/disable worker | `CARDS_ENABLED` env / `cards.enabled` |
| Batch size per pass | `cards.batch-limit` |
| Scan schedule | `cards.scan.delay-ms` / `cards.scan.initial-delay-ms` |
| Cards-per-note mix | `embedder/flashcards/generate.py → N_MCQ / N_OPEN / N_EX` |
| Prompts | `generate.py → GEN_PROMPT / CHECK_PROMPT` |
| Generation model | `SYNTH_MODEL` env (embedder + wrapper, default haiku) |
| Retry budget | `generate.py → MAX_RETRIES` |
| Sandbox limits | `solver_sandbox.py → TIMEOUT_S / MEM_MB / ALLOWED_* / DENIED_NAMES` |
| Random validation samples | `validate.py → K_SAMPLES` |
| Eligibility rule | `CardRepository.findNotesNeedingCards()` WHERE clause |
| Desired retention | `fsrs.desired-retention` property (default 0.9) |
| FSRS weights | `FsrsService.W` (FSRS-6 defaults — regenerate test refs if changed) |
| Bandit arms | `BanditService.ARMS` |
| Context bucketing | `BanditService.bucket()` |
| Score→band thresholds | `ReviewService.Band.fromScore()` (40/70/90) |
| Reward rule (recalled) | `ReviewService.grade()` — `band != HARD` |
