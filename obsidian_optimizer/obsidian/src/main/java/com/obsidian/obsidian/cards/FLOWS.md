# Cards Domain Flows

Files: CardRepository.java, CardGenerationService.java, CardJobWorker.java, CardController.java, FsrsService.java, BanditService.java, ReviewService.java, ReviewController.java, NoteReviewRepository.java, FsrsStateWriter.java, ReviewPreparationService.java
Python agent: embedder/flashcards/generate.py, validate.py, solver_sandbox.py
Architecture: architecture_plans/FLASHCARDS_ARCH.md, architecture_plans/FSRS_REWORK_PLAN.md

---

## Review Flow (FSRS + bandit)

```
POST /api/reviews/grade {notePath, band}     band ∈ HARD/GOOD/EASY/VERY_EASY
  → ReviewService.grade():
    0. lapsed? = existing.due + 7d < now → reviewing >7 days late is a FORCED
       lapse regardless of band (LATE_LAPSE_DAYS).
    1. delayed bandit reward: previous pending (bucket, arm) gets α+1 if recalled
       (Good+ and NOT lapsed) else β+1   (BanditService.reward)
    2. FSRS-6 state update (FsrsService):
         lapsed → FsrsService.forget() (w11-w14: stability collapses, D rises)
         else   → FsrsService.review() (recall path; VERY_EASY == FSRS Easy)
    3. bandit (Thompson Sampling, Beta per bucket×arm) samples arm
       m ∈ {0.7, 0.85, 1.0, 1.2, 1.5}; due = now + round(fsrsInterval × m)
       — Option A: the arm scales the SCHEDULED DATE only, never stored S/D
  → FsrsStateWriter.write(): DB note_reviews upsert + frontmatter mirror together

GET /api/reviews/due?limit=50 → notes due now, most overdue first
```

To change the late-lapse window: `ReviewService.LATE_LAPSE_DAYS`.
To change the lapse math: `FsrsService.forget()` (regenerate refs).

**State lives in BOTH Postgres and frontmatter** — `FsrsStateWriter` is the ONLY
writer and writes both together so they can't drift (D1/D2 in FSRS_REWORK_PLAN).
- Postgres `note_reviews` = the query source (the `/due` index).
- Note frontmatter (`fsrs-s`, `fsrs-d`, `fsrs-last`, `fsrs-arm`, `fsrs-bucket`,
  plus legacy `sr-due`/`sr-interval`) = the offline / volume-reset mirror.
- `FsrsStateWriter.read()` is DB-first; on a miss (fresh machine, nuked volume,
  external Obsidian edit) it **hydrates from the frontmatter mirror** and
  backfills the DB. This is what makes a volume reset recoverable.
- `sr-ease` is preserved untouched (legacy Obsidian-SR field, no longer used).

**On-demand prep** (`ReviewPreparationService.prepare`, called at the top of
`AssignmentService.build`): when you open a note to review in flashcards mode and
the background jobs haven't reached it, it (1) migrates legacy → FSRS preserving
the timeline (`FsrsStateWriter.normalizeLegacy`: S ≈ sr-interval, D from ease, no
reschedule), (2) generates cards if none exist — recorded against **body_hash**
so `CardJobWorker` skips it, (3) chunk+embeds (`EmbeddingService.indexNote`).
Idempotent + best-effort; a re-review of a prepared note is a no-op.

**UI modes** (`flashcardsEnabled` setting, toggle in Settings → Review):
- ON: ReviewPage → FlashcardSession.jsx — builds a tiered-knapsack assignment for
  the note, verifies each answer server-side, completes → band + next due shown.
- OFF: ReviewPage → SlideshowReview (in ReviewPage.jsx) — four band buttons →
  POST /reviews/grade. ReviewRating.jsx (list dropdown) posts the same.

Context buckets: difficulty {<4, 4-7, >7} × stability {<7d, 7-30d, >30d} —
9 buckets × 5 arms. Historical recall rate as context: deferred until attempt
history accumulates.

FsrsService (recall AND forget paths) is pinned against py-fsrs outputs
(FsrsServiceTest); reference values regenerate via `embedder/_fsrs_reference.py`.
Subtlety: difficulty mean-reversion uses the UNCLAMPED Easy initial difficulty
(negative with default weights) — matching py-fsrs exactly.

---

## Generation Flow

```
CardJobWorker @Scheduled (default every 30min, 2min after startup)
  → CardRepository.findNotesNeedingCards(batchLimit)
      SQL diff: notes WHERE sr_due IS NOT NULL
        AND no ACTIVE cards with source_hash == notes.body_hash
        AND no card_gen_attempts row for that (path, body_hash)
      body_hash = SHA-256 of the frontmatter-stripped note. Keying on it (not
      content_hash) means the sr-due rewrite on every review and chrono's date
      fixes — frontmatter-only changes — do NOT re-trigger generation.
        AND no PENDING pending_image_jobs row for the note — cards wait for image
        captioning to finish (SKIPPED/DONE don't block) because generation now
        injects the image text; this also enforces images-before-cards ordering.
  → per note: CardGenerationService.generateFor(), then recordAttempt(path, hash)
      ONLY if the embedder answered — zero-yield generations don't retry
      (credits), but transport failures (wrapper down) retry next cycle
  → POST embedder /flashcards/generate {note_path, source_hash}
      embedder reads the note from its read-only /vault mount
      → flashcards/generate.py:
          PASS 1: GEN_PROMPT → host-wrapper POST /complete (LLM router: free
                  providers first, claude CLI subscription credits LAST —
                  never the Anthropic API; see host-wrapper/FLOWS.md)
          PASS 2: blind self-check — model re-answers its own mcq/exercise
                  questions without seeing answers; mismatches dropped
          validate.py: schema + solver sandbox checks; failures re-prompted,
                  MAX_RETRIES=2, survivors stored
      → upsert into cards (note_path + card_hash dedupe); cards from older note
        versions are KEPT ACTIVE (never auto-archived) — they stay in the review
        draw pool. Removal is user-only (explicit delete).
```

No queue table (deviation from FLASHCARDS_ARCH's pending_card_jobs): the
notes.body_hash ↔ cards.source_hash diff IS the work list — covers app edits,
sync downloads, chrono rewrites, and external Obsidian edits with zero
call-site hooks. The attempt ledger (card_gen_attempts) bounds retries.

## Assignment Flow (sessions)

```
POST /api/assignments {scope, points}        scope = note path or folder prefix
  → AssignmentService.build():
      reviewPrep.prepare(scope)                  on-demand normalize+cards+embed
      per-note TIERED KNAPSACK (Tier: basic 1-2 / mid 3 / advanced 4-5):
        pass 1 covers each tier the note has; pass 2 fills round-robin up to
        MAX_PER_TIER (2) and MAX_CARDS_PER_NOTE (6); folder scope capped at
        SESSION_MAX_CARDS (30). Draws via AssignmentRepository.drawCardInTier
        (per-(note,tier) bag: drawn_cycle < cycle, refill bumps cycle, excludes
        ids already picked this session). `points` is now an optional cap.
      exercises rolled NOW via embedder /flashcards/roll → params + rendered +
      expected answer frozen into assignments.variants
POST /api/attempts {assignmentId, cardId, answer}
  → mcq: index compare · exercise: frozen expected (numeric tolerance /
    normalized string) · open: embedder /flashcards/judge (cosine bands
    0.70/0.85, middle band → wrapper CLI judge with key_points rubric)
  → verdict CORRECT (full difficulty points) / PARTIAL (half) / WRONG (0)
POST /api/assignments/{id}/complete
  → per-note score = Σ earned / Σ difficulty (GROUP BY note)
  → ReviewService.Band.fromScore → grade() → FSRS + bandit → due dates
```

## REST Endpoints (session auth)

| Method | Path | Does |
|---|---|---|
| GET | `/api/cards?notePath=` | ACTIVE cards for a note |
| GET | `/api/cards/stats` | active/archived/notes-with-cards counts |
| POST | `/api/cards/generate {notePath}` | force generation now, synchronous |
| GET | `/api/reviews/due` | notes due per FSRS (+bandit), most overdue first |
| POST | `/api/reviews/grade {notePath, band}` | manual grade (slideshow mode) |
| POST | `/api/assignments {scope, points}` | build a session |
| POST | `/api/attempts {assignmentId, cardId, answer}` | verify + record one answer |
| POST | `/api/assignments/{id}/complete` | score → bands → schedule notes |

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
| Card diff key (body_hash) | set: `ImageScanService.registerImages` via `MarkdownPreprocessor.stripFrontmatter`; read: `findNotesNeedingCards` |
| Card archiving | none — never auto-archived (`generate.py _store`); removal is user-only (delete) |
| Desired retention | `fsrs.desired-retention` property (default 0.9) |
| FSRS weights | `FsrsService.W` (FSRS-6 defaults — regenerate test refs if changed) |
| Lapse / forget math | `FsrsService.forget()` (w11-w14) |
| Late-lapse window | `ReviewService.LATE_LAPSE_DAYS` (7) |
| Bandit arms | `BanditService.ARMS` |
| Context bucketing | `BanditService.bucket()` |
| Score→band thresholds | `ReviewService.Band.fromScore()` (40/70/90) |
| Reward rule (recalled) | `ReviewService.grade()` — `!lapsed && band != HARD` |
| Single state write path | `FsrsStateWriter` (DB + frontmatter; never write `note_reviews` directly) |
| Frontmatter FSRS fields | `FrontmatterRewriter.FsrsFields` / `writeFsrs` |
| Legacy → FSRS seed policy | `FsrsStateWriter.seedFromLegacy` (+ `FsrsService.easeToDifficulty`) |
| On-demand review prep | `ReviewPreparationService.prepare` |
| Card tiers / session caps | `AssignmentService.Tier` / `MAX_PER_TIER` / `MAX_CARDS_PER_NOTE` / `SESSION_MAX_CARDS` |
| Tier bag draw | `AssignmentRepository.drawCardInTier` |
| Content hashing | `common/ContentHashing.sha256` (single hash fn for all pipelines) |
