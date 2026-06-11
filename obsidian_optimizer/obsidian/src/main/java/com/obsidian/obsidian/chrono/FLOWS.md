# Chrono Domain Flows

Files: ChronoService.java, ChronoController.java, FileMoverService.java, FileCheckerService.java, BankruptcyService.java, SpreadService.java, FrontmatterRewriter.java, FrontmatterChecker.java

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
  1. FileMoverService.run(vaultRoot)          — non-recursive vault-root scan
  2. FileCheckerService.run(mdFiles, checker) — default: FrontmatterRewriter::hasInvalidDate
  3. BankruptcyService.run(mdFiles, limit)    — limit from SettingsRepository.getBankruptcyLimit()
  4. SpreadService.run(mdFiles, max)          — max from SettingsRepository.getMaxDailyReviews()
  5. FileRepository.triggerDeltaSync()        — delta resync so DB reflects modified files
  6. Hash loop: sha256(file) vs notes.content_hash — for every changed file
     (chrono rewrites from steps 2-4 AND external Obsidian edits):
     imageScanService.registerImages() + syncQueueRepo.markPending()
     — without the markPending, the 2am run's changes never reached Drive until restart
  7. SettingsRepository.set("chronoLastRunDate", today)
```

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

## FileCheckerService

Walks `mdFiles`, calls `FrontmatterChecker.needsFix(path)`. If true: resets frontmatter to `{ today+3d, interval=3, ease=200 }` via `FrontmatterRewriter.write()`.  
Current checker: `FrontmatterRewriter::hasInvalidDate` — detects Obsidian SR `"Invalid date"` on line 2.

To change the check: pass a different `FrontmatterChecker` lambda to `FileCheckerService.run()`

---

## BankruptcyService

Collects notes where `sr-due < today`. If count ≥ `bankruptcyLimit` → bankruptcy declared.  
Tiered interval reduction:
- `≤7d → 2`
- `≤30d → interval/2`
- `≤90d → 21`
- `>90d → 45`

Ease: `max(215, ease/2)`. Load-balanced scheduling: heap for long/very-long tiers, `pickDate` for short/medium.  
`MIN_INTERVAL=2`, `MIN_EASE=215` — hardcoded constants, not in settings.

---

## SpreadService

Groups notes by day-delta from today. Cascades overflow forward until no day exceeds `maxDailyReviews`. Within overloaded day: lowest-ease notes stay (hardest first), overflow → day+1.  
Works for both future and overdue (negative delta) notes.

---

## FrontmatterRewriter

Shared utility used by `FileCheckerService` and `BankruptcyService`.

`read(Path)` → `SrFields(due, interval, ease)` or null if no valid sr-due  
`write(Path, SrFields)` → rewrites `sr-due/sr-interval/sr-ease` in place, preserves line endings  
`hasInvalidDate(Path)` → true if line 2 ends with `"Invalid date"`

**Frontmatter-scoped**: read/write only touch lines strictly between the opening and
closing `---` (`frontmatterBounds()`). An `sr-due:` mention in a note body or code
fence is never parsed or rewritten — matches `FrontmatterParser` (notes package).
Files without a frontmatter block are skipped entirely.

---

## Settings Keys Used

| Key | Consumer |
|---|---|
| `maxDailyReviews` (default 30) | `SpreadService` |
| `bankruptcyLimit` (default 200) | `BankruptcyService` |
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
| Bankruptcy tiers | `BankruptcyService` interval constants |
| Ease floor | `BankruptcyService.MIN_EASE` |
| Max daily reviews | Settings UI → `SettingsRepository.getMaxDailyReviews()` |
| Bankruptcy limit | Settings UI → `SettingsRepository.getBankruptcyLimit()` |
| Invalid date detection | `FrontmatterRewriter.hasInvalidDate()` |
| FileMover extensions | `FileMoverService.*_EXTS` sets |
| CheckerService predicate | `ChronoService.runAllJobs()` — pass different `FrontmatterChecker` lambda |
