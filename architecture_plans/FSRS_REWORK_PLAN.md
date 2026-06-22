# FSRS + Bandit Rework Plan

Status: **AWAITING PERMIT** — decisions locked, nothing implemented.
Supersedes the half-baked first cut described in `FLASHCARDS_ARCH.md`.

Branch: create `feat/fsrs-frontmatter-rework` off `master` (current
`fix/http1-and-cudnn9` is unrelated embedder work). Commit per stage.

---

## Locked decisions

| # | Decision | Choice |
|---|---|---|
| D1 | FSRS state source of truth | **Both**: Postgres `note_reviews` is the query source; note **frontmatter is a full mirror** so reviews survive volume resets and work offline/mobile. |
| D2 | Bandit pending (arm + bucket) location | **Frontmatter + DB** (Option A). The whole scheduling decision round-trips; a review graded anywhere can credit the right Beta cell. Survives volume nukes. |
| D3 | Forgotten / very-late penalty | **Proper FSRS forget path (w11–w14) + 7-day rule**: reviewing a note >7 days after its due date is a forced lapse regardless of the band pressed. Bankruptcy applies the same lapse en masse. |
| D4 | Card selection for a test | **Per-note tiered knapsack**: cover basic / mid / advanced by difficulty, capped count, so `score → band` is a representative signal. |
| — | Reward function | **Deferred** to a separate design + permit after Stage 1. Direction: realized-vs-target retrievability ratio ("binary-search the latest day I still remember") replacing today's "recalled?" which collapses every arm to 0.7. |

---

## Frontmatter schema (the mirror)

Kept for Obsidian-SR / legacy queue / human view:
- `sr-due:` — scheduled calendar date (drives the review queue, chrono, Obsidian-SR plugin)
- `sr-interval:` — scheduled days (derived display; = `round(base × arm)`)
- `sr-ease:` — **preserved untouched** (legacy Obsidian-SR field; no longer used by our logic)

New FSRS state (the real memory model):
- `fsrs-s:` — stability
- `fsrs-d:` — difficulty
- `fsrs-last:` — last review date (for elapsed-days computation offline)
- `fsrs-arm:` — pending bandit multiplier
- `fsrs-bucket:` — pending bandit context bucket

All written together on every grade. Notes without a frontmatter block are
skipped (same discipline as today's `FrontmatterRewriter.frontmatterBounds`).

---

## Single write path (prevents DB ↔ frontmatter divergence)

New `FsrsStateWriter` (cards package) is the **only** thing that mutates FSRS
state. Both review and chrono go through it:

- `write(notePath, FsrsState, due, arm, bucket)` → upsert `note_reviews` **and**
  rewrite frontmatter (`fsrs-*` + `sr-due`/`sr-interval`), then
  `FileRepository.reindexAfterExternalWrite`.
- `reschedule(notePath, newDue)` → moves the calendar date only (DB + `sr-due`),
  never touches S/D. Used by Spread (Option-A philosophy: scheduling ≠ memory).

`read(notePath)` → DB first; **if the DB row is missing, hydrate from
frontmatter** and backfill the DB. This is what makes a volume reset recoverable.

---

## Stage 1 — State in frontmatter + DB

1. Extend `FrontmatterRewriter` (chrono) to read/write the full field set above
   via a richer record (e.g. `FsrsFields`); keep a thin legacy `SrFields` view
   only where still needed.
2. Add `FsrsStateWriter` + frontmatter hydration read path.
3. `ReviewService.grade()`:
   - read existing state via `FsrsStateWriter.read` (DB → frontmatter fallback);
   - on missing state, `FsrsService.initialState` (first review = fresh FSRS,
     legacy `ease` is *not* mapped — accepted one-time reset at migration);
   - write back through `FsrsStateWriter` (replaces `writeSrFrontmatter`).
4. Startup/scan hydration: notes with `fsrs-*` frontmatter but no DB row get
   their `note_reviews` backfilled (piggyback on the notes-index scan).
5. Tests: `ReviewServiceTest` — grade writes both stores; DB-wipe → next grade
   recovers state from frontmatter.

**Commit:** "FSRS state mirrored to frontmatter with DB-rebuild recovery"

---

## Stage 2 — FSRS forget path + 7-day rule

1. `FsrsService`:
   - add `GRADE_AGAIN = 1`;
   - `forget(state, elapsedDays)`:
     `Sf = w11 · D^(-w12) · ((S+1)^w13 − 1) · exp(w14 · (1−r))`,
     `newS = clamp(min(Sf, S))`, `newD = nextDifficulty(D, GRADE_AGAIN)`.
2. `ReviewService.grade()`: compute lateness from existing `due`. If
   `now > due + 7d` → take the **forget** path regardless of band; bandit reward
   for the prior arm = **not recalled** (β+1). Otherwise the existing recall path.
3. Regenerate reference values: add `Rating.Again` cases to
   `embedder/_fsrs_reference.py`, pin in `FsrsServiceTest`.

**Commit:** "FSRS lapse path + 7-day-late forced forget"

---

## Stage 3 — Chrono rewrite onto FSRS

1. `BankruptcyService`: overdue = `fsrs-due < today`. On bankruptcy, apply
   `FsrsService.forget` to each overdue note (this *is* the mass-lapse), then
   `intervalDays(newS)` → load-balanced due via `FsrsStateWriter.write`.
   Retire the `interval`-tier / `ease/2` constants.
2. `SpreadService`: read `fsrs-due` + `fsrs-d`. Cap per day; keep
   **highest-difficulty** notes on the crowded day (hardest stay), overflow the
   easiest forward via `FsrsStateWriter.reschedule` (date only).
3. ~~`FileCheckerService`: invalid-date reset~~ — SUPERSEDED: FileChecker was
   later removed entirely (our FSRS date writer can't produce "Invalid date").
4. Tests: `BankruptcyServiceTest` (overdue → forget + reschedule),
   `SpreadServiceTest` (FSRS-difficulty ordering, date-only moves).

**Commit:** "Chrono (bankruptcy/spread) operate on FSRS state"

---

## Stage 4 — Per-note tiered knapsack selection + scoring

1. `AssignmentService.build`: replace point-budget greedy with per-note tiered
   selection. Tiers by difficulty (basic 1–2, mid 3, advanced 4–5 — tunable
   constants). Per note: ≥1 from each present tier, capped total
   (`MAX_PER_TIER`, `MAX_CARDS_PER_NOTE`). Keep the cycle/bag draw for
   without-replacement across sessions, constrained to the chosen tier.
   Folder scope: per-note knapsack with a per-session note cap.
2. Scoring (`perNoteScores`) stays `Σ earned / Σ difficulty` — now representative
   because coverage is guaranteed; `Band.fromScore` (40/70/90) unchanged.
3. `AssignmentController` / `FlashcardSession.jsx`: `points` becomes an optional
   cap; default request asks for tiered coverage.
4. Tests: `AssignmentService` knapsack coverage; `FlashcardSession.test.jsx`
   selection shape.

**Commit:** "Per-note tiered knapsack card selection"

---

## Stage 5 — Reward function (separate design + permit)

Out of scope for this permit. After Stage 1 lands, design the realized-vs-target
retrievability reward, including the offline fallback (the `bandit_arms` Beta
table is DB-only; mobile-offline grading falls back to arm = 1.0, pure FSRS).

---

## Stage 6 — FLOWS + docs (final, describes the shipped architecture)

Done last so the docs reflect the end state, not intermediate stages. Each prior
stage ships its own tests (listed above); this stage is documentation only.

1. `cards/FLOWS.md` — rewrite the Review Flow section: frontmatter+DB mirror,
   `FsrsStateWriter` single write path, lapse/7-day rule, knapsack selection.
   Update the Change Index table.
2. `chrono/FLOWS.md` — bankruptcy-as-lapse, FSRS-difficulty spread, new fields.
3. `FrontmatterRewriter` notes in `chrono/FLOWS.md` — new `fsrs-*` field set.
4. Add a `## Technology Notes` entry: DB↔frontmatter dual-store, why both, what
   breaks on volume reset, offline bandit fallback.

**Commit:** "FLOWS: document FSRS frontmatter+DB rework"

---

## Test summary (per the request — coverage lands with each stage)

| Stage | Tests |
|---|---|
| 1 | `ReviewServiceTest`: dual-store write; DB-wipe → frontmatter recovery |
| 2 | `FsrsServiceTest`: lapse/forget refs (py-fsrs `Rating.Again`); `ReviewServiceTest`: >7d late → forced forget + β reward |
| 3 | `BankruptcyServiceTest`, `SpreadServiceTest` on FSRS state |
| 4 | `AssignmentService` knapsack tier coverage; `FlashcardSession.test.jsx` |

---

## Migration / risk notes

- **One-time FSRS reset**: existing notes carry legacy `sr-due/interval/ease`
  only. First post-rework review seeds fresh FSRS state from the band; `sr-due`
  is preserved so nothing falls out of the queue. Accepted, documented.
- **DB/frontmatter divergence** is structurally prevented by routing every
  mutation through `FsrsStateWriter` (no direct `note_reviews` writes elsewhere).
- **Bankruptcy now changes memory state** (lapse), not just dates — a heavier
  operation than before. Threshold (`bankruptcyLimit`) still gates it.
- FLOWS updates: `cards/FLOWS.md`, `chrono/FLOWS.md`.
```
```
