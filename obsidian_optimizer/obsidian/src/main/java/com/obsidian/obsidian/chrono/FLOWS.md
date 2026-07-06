# Chrono Domain Flows

Files: ChronoService.java, ChronoController.java, FileMoverService.java, BankruptcyService.java, SpreadService.java, FrontmatterRewriter.java
Also invoked here: cards/FlaggedCardRegenService.java (step 3b — see cards/FLOWS.md "Flag + regen")

---

## Trigger Points

| Trigger | When |
|---|---|
| `ChronoService.@PostConstruct onStartup()` | After startup sync — if `chronoLastRunDate` is blank or before today |
| `@Scheduled(cron = "0 0 2 * * *")` | 2 am daily |
| `POST /api/chrono/run` | Manual (auth required) |
| `GET /api/chrono/status` | Returns `{ lastRunDate }` (public) |

`@EnableScheduling` is on `ObsidianApplication`.

---

## Execution Order

```
ChronoService.runAllJobs()
  1. FileMoverService.run(vaultRoot)              — non-recursive vault-root scan
  2. BankruptcyService.run(mdFiles, limit, neglectDays)
        limit       from SettingsRepository.getBankruptcyLimit()
        neglectDays from SettingsRepository.getChronicNeglectDays()
  3. SpreadService.run(mdFiles, max)              — max from SettingsRepository.getMaxDailyReviews()
  3b. FlaggedCardRegenService.run()               — refill user-flagged bad cards (cards/FLOWS.md
        "Flag + regen"): one feedback-aware replacement per flag. LLM-bound; gated on
        cards.enabled + flashcardsEnabled. Marks flags serviced only when the embedder answered.
  4. FileRepository.triggerDeltaSync()            — delta resync so DB reflects modified files
  5. Hash loop: sha256(file) vs notes.content_hash — for every changed file
     (chrono rewrites from steps 2-3 AND external Obsidian edits):
     imageScanService.registerImages() + syncQueueRepo.markPending()
     — without the markPending, the 2am run's changes never reached Drive until restart
  6. SettingsRepository.set("chronoLastRunDate", today)
```

The old FileChecker step is gone — it only fixed the Obsidian-SR "Invalid date"
corruption, which our own FSRS date writer (`FrontmatterRewriter.writeFsrs`) can't
produce. Late-lapse detection also no longer lives at review time; it's the
chronic-neglect pass inside step 2 (see below).

`FileRepository.listMdPaths()` called once in `runAllJobs()`; result passed to all services. Reuses `bfsDiskFiles()` + `EXCLUDED_DIRS` — `_trash/` and `resources/` skipped.

---

## FileMoverService

Non-recursive scan of vault root. Moves by extension:  
`.png/.jpg/.jpeg/.gif/.webp` → `resources/images`  
`.pdf` → `resources/pdf`  
`.mp4/.mov/.mkv` → `resources/videos`  
Creates subdirs if missing.

To add an extension: `FileMoverService.IMAGE_EXTS / PDF_EXTS / VIDEO_EXTS` sets

---

## BankruptcyService  *(the FSRS lapse job — two passes)*

Collects notes where `sr-due < today` and splits them by how overdue they are.
Per lapsed note (`FsrsService` + `FsrsStateWriter` injected):
- get FSRS state via `FsrsStateWriter.read`; if still legacy, seed it
  (`FsrsStateWriter.seedFromLegacy`: S ≈ sr-interval, D from ease — timeline kept).
- apply `FsrsService.forget()` — the lapse (stability collapses, difficulty rises).
- `newInterval = intervalDays(newS)`; load-balance a due date in `[today+1,
  today+newInterval]` (`leastLoadedDate`).
- write via `FsrsStateWriter.writeState` → DB + frontmatter, pending bandit
  decision preserved (no review happened).

```
Pass 1 — Chronic neglect (ALWAYS runs, no threshold):
  notes overdue > chronicNeglectDays  → lapsed individually
  Stops notes drifting forgotten for months while total overdue stays below the
  bankruptcy limit. This is what replaced the old per-review LATE_LAPSE check in
  ReviewService — a note you never open still gets lapsed before you next see it.

Pass 2 — Mass bankruptcy (threshold gate):
  if total overdue (chronic + standard) >= bankruptcyLimit
     → lapse ALL remaining standard overdue notes too
```

`BankruptcyResult(overdueCount, chronicNeglected, declared, rescheduled)`.

**No bandit reward here, on purpose.** A bankruptcy/neglect lapse is exogenous
(the user didn't open the app), not evidence the interval was wrong — feeding it
to the bandit would punish good long arms for the user's absence. The bandit only
learns from genuine reviews (`ReviewService` → `BanditService`; see cards/FLOWS.md).

---

## SpreadService  *(calendar only — never touches FSRS memory)*

Groups notes by day-delta from today. Cascades overflow forward until no day
exceeds `maxDailyReviews`. On an overloaded day the **hardest stay** (highest
FSRS difficulty; legacy notes via `FsrsService.easeToDifficulty`), easiest spill
to day+1. Moves the date only: FSRS notes via `FsrsStateWriter.reschedule` (DB +
frontmatter), legacy notes via direct `FrontmatterRewriter.write`. Works for both
future and overdue (negative delta) notes.

---

## FrontmatterRewriter

Shared utility used by `BankruptcyService`, `SpreadService` and (FSRS mirror)
`FsrsStateWriter`.

Legacy sr-fields:
`read(Path)` → `SrFields(due, interval, ease)` or null if no valid sr-due  
`write(Path, SrFields)` → rewrites `sr-due/sr-interval/sr-ease` in place

FSRS mirror (the new state carrier):
`readFsrs(Path)` → `FsrsFields(due, interval, stability, difficulty, lastReview,
arm, bucket)`, or null if no `fsrs-s` yet (legacy-only note)  
`writeFsrs(Path, FsrsFields)` → **upserts** `fsrs-*` + `sr-due`/`sr-interval`
(inserts missing keys before the closing `---`), preserves `sr-ease` and line
endings; returns false (no-op) when the note has no frontmatter block

**Frontmatter-scoped**: read/write only touch lines strictly between the opening and
closing `---` (`frontmatterBounds()`). An `sr-due:` mention in a note body or code
fence is never parsed or rewritten — matches `FrontmatterParser` (notes package).
Files without a frontmatter block are skipped entirely.

---

## Settings Keys Used

| Key | Consumer |
|---|---|
| `maxDailyReviews` (default 30) | `SpreadService` |
| `bankruptcyLimit` (default 200) | `BankruptcyService` pass 2 (mass) gate |
| `chronicNeglectDays` (default 7) | `BankruptcyService` pass 1 (per-note) threshold |
| `chronoLastRunDate` | `ChronoService` same-day idempotency guard |

---

## Technology Notes

- **Same-day idempotency**: `onStartup()` only runs jobs if `chronoLastRunDate != today`. Safe to restart repeatedly.
- **`@PostConstruct` order dependency**: `ChronoService.onStartup()` fires after `FileRepository.init()` (Spring resolves injection graph). If async sync mode is active, jobs run against a partially-synced DB.
- **File modifications are local**: `FrontmatterRewriter.write()` writes directly to disk. `FileRepository.triggerDeltaSync()` in step 5 picks up those changes.

---

## Change Index

| Thing to change | Where |
|---|---|
| Cron schedule | `ChronoService` `@Scheduled(cron = ...)` |
| Bankruptcy lapse | `BankruptcyService` → `FsrsService.forget` (seeds legacy via `FsrsStateWriter.seedFromLegacy`) |
| Chronic-neglect window | Settings UI → `SettingsRepository.getChronicNeglectDays()` (default 7) |
| Spread hardness order | `SpreadService` (FSRS difficulty / `FsrsService.easeToDifficulty`) |
| Max daily reviews | Settings UI → `SettingsRepository.getMaxDailyReviews()` |
| Bankruptcy limit (mass gate) | Settings UI → `SettingsRepository.getBankruptcyLimit()` |
| FileMover extensions | `FileMoverService.*_EXTS` sets |
