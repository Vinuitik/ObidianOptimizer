# Backend Flows

Files: ObsidianApplication.java, MyController.java, FileRepository.java, NoteLinkRepository.java, NoteIndexRepository.java, FrontmatterParser.java, ImageRepository.java, SettingsRepository.java, WebConfig.java, SecurityConfig.java, ServletInitializer.java

---

## Startup

Bean init order (Spring respects dependency graph):
1. `SettingsRepository` — creates `app_settings`, seeds defaults from env vars
2. `NoteLinkRepository` — creates `note_links` + index
3. `NoteIndexRepository` — creates `notes` + index
4. `FileRepository.@PostConstruct init()` — reads vault path from `SettingsRepository` → `bfsDiskFiles()` → `NoteIndexRepository.syncWithDisk()`
   - `startupSyncMode = "blocking"` (default): sync runs before app accepts requests
   - `startupSyncMode = "async"`: sync runs in `CompletableFuture.runAsync()`, app starts immediately

To change port: `application.properties → server.port`

---

## Auth (SecurityConfig.java)

Spring Security session-based single-user auth.  
Credentials in `application.properties`: `app.auth.username`, `app.auth.password` (raw — BCrypt applied at startup)

`POST /login` (form-encoded: `username=&password=`) → Spring Security validates → sets session cookie → returns 200  
`POST /logout` → invalidates session → returns 200  
`GET /me` → 200 + username if authenticated, 401 if not

`GET /settings` public. All other endpoints require session auth.  
To add/remove protected endpoints: `SecurityConfig.filterChain()` `authorizeHttpRequests`

---

## Startup Sync — Delta Algorithm

`NoteIndexRepository.syncWithDisk(diskFiles)`:

```
diskMap = { path → File }           from bfsDiskFiles()
dbMap   = { path → modified_at }    from SELECT path, modified_at FROM notes

for each diskFile:
  if path not in dbMap:             → INSERT (new note)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  elif file.lastModified() > dbMap[path]:  → UPDATE (changed externally)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  else:                             → skip (unchanged, no I/O)

for each dbPath not in diskMap:     → DELETE (removed externally)
  delete() + deleteSource()
```

Only reads file content for new or modified notes — unchanged notes cost zero disk I/O.

---

## FrontmatterParser

`FrontmatterParser.parse(rawContent)` → `NoteMetadata(srDue, srInterval, srEase)`  
Normalises `\r\n` → `\n`, finds `---` … `---` block, splits lines on `:`.  
Returns `null` fields for any key not present or unparseable.  
Keys: `sr-due` (DATE), `sr-interval` (INT), `sr-ease` (INT).

Canonical frontmatter format (written by `createNote`):
```
---
sr-due: yyyy-MM-dd
sr-interval: 3
sr-ease: 200
---
```
To add a new frontmatter field to the index: `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()`.

---

## NoteIndexRepository — notes table

Schema:
```sql
notes(
  path        TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  sr_due      DATE,           -- NULL = no review date
  sr_interval INT,
  sr_ease     INT,
  modified_at BIGINT NOT NULL -- file.lastModified() epoch ms
)
INDEX idx_notes_sr_due ON notes(sr_due)
```

Key methods:
- `syncWithDisk(diskFiles)` — delta sync (see above)
- `forceResync(diskFiles)` — TRUNCATE notes + TRUNCATE note_links → full syncWithDisk
- `getAllPaths()` — `SELECT path FROM notes ORDER BY path`
- `getReviewNotesPaged(offset, limit)` — `WHERE sr_due <= CURRENT_DATE ORDER BY sr_due, path LIMIT limit+1 OFFSET offset` (limit+1 trick avoids COUNT query)
- `upsert(path, title, meta, modifiedAt)` — INSERT … ON CONFLICT DO UPDATE
- `rename(oldPath, newPath, newTitle)` — UPDATE path + title
- `delete(path)` — DELETE

---

## GET /names — All Note Paths

`MyController.getNames()` → `FileRepository.getNoteNames()` → `NoteIndexRepository.getAllPaths()`  
→ `SELECT path FROM notes ORDER BY path`  
No disk I/O. O(N) result scan.

---

## GET /review — Notes Due for Review

`MyController.getReviewNames()` → `FileRepository.getReviewNotesPaged(offset, limit)` → `NoteIndexRepository.getReviewNotesPaged()`  
→ `SELECT path FROM notes WHERE sr_due <= CURRENT_DATE ORDER BY sr_due ASC, path ASC LIMIT ? OFFSET ?`  
`hasMore` detected via limit+1 trick — no separate COUNT query.  
To change review sort: `NoteIndexRepository.getReviewNotesPaged()` ORDER BY clause.

---

## GET /text?noteName={path} — Note Content

`MyController.getText(noteName)` → `FileRepository.getText(path)` → `Files.readString()` → returns raw markdown

---

## GET /images/{filename} — Image Serving

`ImageRepository.getImage(filename)` → `serveFile(settingsRepo.getResourcePath(), filename)` → validate exists → detect MIME → return `ResponseEntity<Resource>`  
Image dir: read from `SettingsRepository` → `app_settings.resourcePath` (default: `VAULT_PATH/resources/images`)

---

## POST /notes — Create Note

`MyController.createNote(CreateNoteRequest{folder, name})`  
→ `FileRepository.createNote(folderPath, name)` → validate folder → create `name.md` with frontmatter skeleton  
→ `FrontmatterParser.parse(initialContent)` → `NoteIndexRepository.upsert()`  
→ `NoteLinkRepository.updateLinks()` → return `{path: absolutePath}`

Initial frontmatter: `---\nsr-due: {today+3d}\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n`  
To change defaults: `FileRepository.createNote()`

---

## PUT /notes — Update Note Content (full replace, fallback)

`MyController.updateNote(UpdateNoteRequest{path, content})`  
→ `FileRepository.updateNote(path, content)` → `Files.writeString()` → `FrontmatterParser.parse()` → `NoteIndexRepository.upsert()` → `NoteLinkRepository.updateLinks()`

---

## PATCH /notes/content — Diff-based Update

`MyController.patchNote(PatchNoteRequest{path, hunks})`  
→ `FileRepository.patchNote(path, hunks)` → apply hunks back-to-front → `Files.writeString()`  
→ `FrontmatterParser.parse(newContent)` → `NoteIndexRepository.upsert()` → `NoteLinkRepository.updateLinks()`

Hunk DTO: `FileRepository.PatchHunk(int startLine, int deleteCount, List<String> insertLines)`

---

## PATCH /notes/rename — Rename Note

`MyController.renameNote(RenameNoteRequest{oldPath, newName})`  
→ `FileRepository.renameNote(oldPath, newName)`
1. `NoteLinkRepository.findSourcesByTarget(oldName)` → backlink list
2. `File.renameTo()` on disk
3. Rewrite `[[oldName]]` → `[[newName]]` in each source file
4. `NoteIndexRepository.rename(oldPath, newPath, newTitle)` — UPDATE path + title
5. `NoteLinkRepository.renameTarget(oldName, newName)` + `renameSource(oldPath, newPath)`

Cross-file rename works for notes written through the app. External edits are caught at next startup sync (delta detects `lastModified` change → `updateLinks` refreshes the row).

---

## DELETE /notes — Soft Delete

`MyController.deleteNote(DeleteNoteRequest{path})`  
→ `FileRepository.softDeleteNote(path)` → move to `ROOT_FILE/_trash/` → `NoteIndexRepository.delete(path)` → `NoteLinkRepository.deleteSource(path)`

---

## GET /settings + PUT /settings

`GET /settings` — public, returns `{vaultPath, resourcePath, reviewPageSize, startupSyncMode}`  
`PUT /settings` — auth required, partial update (all fields optional)

`vaultPath` change → `FileRepository.updateVaultPath()` → validates dir → saves to DB → sets `ROOT_FILE` → `NoteIndexRepository.forceResync()` (TRUNCATE notes + note_links → full delta sync)  
`startupSyncMode` — `"blocking"` or `"async"`, validated in controller  
`reviewPageSize` — 1–500, validated in controller

---

## SettingsRepository — app_settings table

`app_settings(key TEXT PRIMARY KEY, value TEXT)`  
Seeded on first boot from env vars with `ON CONFLICT DO NOTHING`.

| Key | Default | Source |
|---|---|---|
| `vaultPath` | `$VAULT_PATH` | env var |
| `resourcePath` | `$IMAGE_PATH` or `$VAULT_PATH/resources/images` | env var |
| `reviewPageSize` | `20` | hardcoded |
| `startupSyncMode` | `"blocking"` | hardcoded |

To force re-seed: `DELETE FROM app_settings;` → restart.

---

## NoteLinkRepository — note_links adjacency table

`note_links(source_path TEXT, target_name TEXT, PRIMARY KEY(source_path, target_name))`  
Index on `target_name` for O(k) rename lookups.

**Lifecycle:**
- `initSchema()` — `CREATE TABLE IF NOT EXISTS` + index
- `syncWithDisk()` (via `NoteIndexRepository`) — calls `updateLinks()` for every new/changed note
- `truncateLinks()` — truncate only (used before `forceResync`)
- `forceRebuildLinks(paths)` — truncate + full backfill from a path list

**Staleness note:** external Obsidian edits are caught at next startup sync. No mid-session inotify — Docker volume mounts on Windows don't propagate inotify events.

---

## Infrastructure

Three services via `docker-compose.yml`. Start: `.\start.ps1`. Stop: `Ctrl+C`.

### Technology Notes
- **WAR + embedded Tomcat**: `java -jar app.war` is self-executable despite `provided` scope.
- **Volume mount on Windows**: file watching (inotify/WatchService) does NOT propagate from host → container. Startup delta sync handles external edits instead.
- **pgvector container**: `pgvector/pgvector:pg16` — backend waits on `service_healthy`. Data persisted in `postgres_data` volume.
- **Startup sync blocking mode**: app does not accept requests until `NoteIndexRepository.syncWithDisk()` completes. On large vaults with no changes, this is fast (only a DB query + Set comparison). On first boot or after many external edits, it reads changed files from disk.
- **Startup sync async mode**: app starts immediately. `getNoteNames()` and `getReviewNotesPaged()` return partial results during sync. Choose this for faster boot at the cost of a brief stale window.

---

## Change Index

| Thing to change | Where |
|---|---|
| Vault root path | Settings page → `PUT /api/settings` → `FileRepository.updateVaultPath()` |
| Image directory | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Review page size | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Startup sync mode | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Server port | `application.properties → server.port` |
| Auth credentials | `application.properties → app.auth.username / app.auth.password` |
| Frontmatter keys indexed | `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()` |
| Review sort order | `NoteIndexRepository.getReviewNotesPaged()` ORDER BY |
| Directories skipped in BFS | `FileRepository.EXCLUDED_DIRS` |
| Initial note frontmatter | `FileRepository.createNote()` |
| Diff algorithm | `frontend/src/utils/diff.js lcsBacktrack()` |
| Patch hunk DTO | `FileRepository.PatchHunk` |
| Protected vs public endpoints | `SecurityConfig.filterChain()` |
| Wiki-link regex (extract) | `NoteLinkRepository.WIKI_LINK` pattern |
| Wiki-link regex (rewrite) | `NoteLinkRepository.rewriteLinks()` |
| Postgres connection | `application.properties` (overridden by `SPRING_DATASOURCE_*` env vars) |
| Force notes full re-index | `TRUNCATE notes; TRUNCATE note_links;` in psql → restart backend |
| Run unit tests | `mvn test -Dtest="!*IT"` |
| Run integration tests | `mvn test -Dtest="*IT"` (Docker required) |
| Add a new unit test | `src/test/java/.../` — extend relevant `*Test.java` |
| Add a new IT test | `NoteLifecycleIT.java` (DB/file lifecycle) or `ChronoServiceIT.java` (jobs) |
| Change IT Postgres image | `NoteLifecycleIT.java` + `ChronoServiceIT.java` `PostgreSQLContainer<>(...)` |
| Change IT vault path | Static initializer block in each IT class |
| Mockito strict mode | `@MockitoSettings(strictness = ...)` on the test class |

---

---

## Chrono Service

Files: ChronoService.java, FileMoverService.java, FileCheckerService.java, BankruptcyService.java, SpreadService.java, FrontmatterRewriter.java, FrontmatterChecker.java

Daily maintenance jobs run in sequence once per day. Last run date persisted in `app_settings.chronoLastRunDate`.

### Trigger points

- `@PostConstruct` on `ChronoService` — if `chronoLastRunDate` is blank or before today, runs immediately after startup sync
- `@Scheduled(cron = "0 0 2 * * *")` — runs at 2am daily
- `POST /api/chrono/run` — manual trigger (auth required)
- `GET /api/chrono/status` — returns `{ lastRunDate }` (public)

`@EnableScheduling` is required — enabled on `ObsidianApplication`.

### Execution order

```
ChronoService.runAllJobs()
  1. FileMoverService.run(vaultRoot)        — non-recursive scan of vault root
  2. FileCheckerService.run(mdFiles, checker) — default checker: FrontmatterRewriter::hasInvalidDate
  3. BankruptcyService.run(mdFiles, limit)  — reads bankruptcyLimit from settings
  4. SpreadService.run(mdFiles, max)        — reads maxDailyReviews from settings
  5. FileRepository.triggerDeltaSync()      — delta resync so DB reflects modified files
  6. settingsRepo.set("chronoLastRunDate")  — mark today done
```

`FileRepository.listMdPaths()` — called once in `runAllJobs()`, result passed to all services. Reuses `bfsDiskFiles()` + `EXCLUDED_DIRS` so `_trash/` and `resources/` are skipped.

### FileMoverService

Scans vault root (non-recursive). Moves: `.png/.jpg/.jpeg/.gif/.webp` → `resources/images`, `.pdf` → `resources/pdf`, `.mp4/.mov/.mkv` → `resources/videos`. Creates subdirs if missing.  
To add an extension: `FileMoverService.IMAGE_EXTS / PDF_EXTS / VIDEO_EXTS` sets.

### FileCheckerService

Walks `mdFiles`, calls `FrontmatterChecker.needsFix(path)`. If true: resets to `{today+3, interval=3, ease=200}` via `FrontmatterRewriter.write()`.  
Current checker: `FrontmatterRewriter::hasInvalidDate` — detects Obsidian SR `"Invalid date"` on line 2.  
To change the check: pass a different `FrontmatterChecker` lambda to `FileCheckerService.run()`.

### BankruptcyService

Collects all notes where `sr-due < today`. If count ≥ `bankruptcyLimit` → bankruptcy declared.  
Tiered interval reduction: `≤7d → 2`, `≤30d → interval/2`, `≤90d → 21`, `>90d → 45`.  
Ease: `max(215, ease/2)`.  
Load-balanced scheduling: heap for long/very-long tiers, `pickDate` for short/medium.  
Constants: `MIN_INTERVAL=2`, `MIN_EASE=215`, tier boundaries and capped intervals are hardcoded — not in settings.

### SpreadService

Groups all notes by day-delta from today. Cascades overflow forward day-by-day until no day exceeds `maxDailyReviews`. Within each overloaded day, lowest-ease notes stay (hardest first), overflow goes to day+1.  
Works on both future and overdue notes (negative deltas cascade forward through today and beyond).

### FrontmatterRewriter (shared utility)

`read(Path)` → `SrFields(due, interval, ease)` or null if no valid sr-due.  
`write(Path, SrFields)` → rewrites `sr-due/sr-interval/sr-ease` in place, preserves line endings.  
`hasInvalidDate(Path)` → true if line 2 ends with `"Invalid date"`.

### Settings keys

| Key | Default | Tunable in UI |
|---|---|---|
| `maxDailyReviews` | `30` | Yes |
| `bankruptcyLimit` | `200` | Yes |
| `chronoLastRunDate` | `""` | No (internal) |

---

## Testing

Files: FrontmatterRewriterTest.java, FileMoverServiceTest.java, FileCheckerServiceTest.java, BankruptcyServiceTest.java, SpreadServiceTest.java, FileRepositoryPatchTest.java, NoteLinkRepositoryTest.java, MyControllerTest.java, NoteLifecycleIT.java, ChronoServiceIT.java, ObsidianApplicationTests.java

### Run commands

```powershell
# Unit tests only (fast, no Docker)
mvn test -Dtest="!*IT" --no-transfer-progress

# Integration tests only (pulls postgres:16 via Testcontainers — Docker must be running)
mvn test -Dtest="*IT" --no-transfer-progress

# All tests
mvn test --no-transfer-progress
```

### Layer 1 — Unit tests (no Spring, no DB)

All use `@ExtendWith(MockitoExtension.class)` + `@TempDir` for real temp files.  
`@MockitoSettings(strictness = Strictness.LENIENT)` on `FileRepositoryPatchTest` — shared `@BeforeEach` stubs are unused by early-exit tests (null/empty hunks, missing file).  
`@PostConstruct` is NOT invoked in plain Mockito tests — no Spring context.

| Test class | What it covers |
|---|---|
| `FrontmatterRewriterTest` | `read()`, `write()`, `hasInvalidDate()` — CRLF preservation, null sr-due, round-trip |
| `FileMoverServiceTest` | All 9 media extensions → correct subdirs; non-recursive; unknown ext stays |
| `FileCheckerServiceTest` | Fix triggered on invalid date; reset to `{today+3, 3, 200}`; valid dates untouched |
| `BankruptcyServiceTest` | Threshold (count ≥ limit), 4 tier intervals, ease floor (215), future notes excluded |
| `SpreadServiceTest` | Within-cap no-op, overflow cascade, lowest-ease stays, overdue cascade, empty list |
| `FileRepositoryPatchTest` | Single insert/delete/replace, multi-hunk back-to-front, CRLF, out-of-range throws |
| `NoteLinkRepositoryTest` | `extractTargets()` (dedupe, path prefix, display text), `rewriteLinks()` (all forms) |

### Layer 2 — Controller tests (MockMvc + Mockito)

`MyControllerTest` — `@WebMvcTest(MyController.class)` + `@MockBean` for `FileRepository`, `SettingsRepository`, `ChronoService`.

Endpoints covered: `GET /names`, `GET /review`, `GET /text`, `POST /notes`, `PATCH /notes/content`, `PATCH /notes/rename`, `DELETE /notes`, `GET /chrono/status`, `POST /chrono/run`.

Settings validation: `maxDailyReviews`, `bankruptcyLimit`, `reviewPageSize`, `startupSyncMode`.

### Layer 4 — Integration tests (Testcontainers + real Postgres)

Both IT classes share the same pattern:

```
@Testcontainers
@SpringBootTest(webEnvironment = NONE)
class *IT {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
  static final Path VAULT;  // static initializer — runs before Spring reads @DynamicPropertySource
  static { VAULT = Files.createTempDirectory(...); }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry r) {
    r.add("VAULT_PATH", VAULT::toString);
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    ...
  }
  @AfterEach void cleanAll() { ... noteIndex.forceResync(List.<File>of()); }
}
```

**Why static initializer instead of `@BeforeAll`:** `@DynamicPropertySource` suppliers are evaluated during Spring context creation, which may run before `@BeforeAll`. The static field initializer is guaranteed to run when the class loads — before any JUnit or Spring machinery.

**Why `postgres:16` not `pgvector/pgvector:pg16`:** pgvector features are not yet used in production code (planned for ML layer). Standard `postgres:16` starts faster on CI.

#### NoteLifecycleIT

`@MockBean ChronoService` — prevents `onStartup()` from running jobs against the test vault.

Covers: `createNote()` upserts to DB, delta sync (insert/skip unchanged/delete removed), `patchNote()`, `renameNote()` + backlink rewrite, `softDeleteNote()` → `_trash/`, review queue due-filter + `hasMore` flag, `NoteLinkRepository` index update + rename.

#### ChronoServiceIT

Real `ChronoService` bean. `@BeforeEach` resets `chronoLastRunDate = ""` so `onStartup()` ran once during context creation (empty vault); each test pre-populates vault then calls `runAllJobs()` directly.

Covers: `FileMoverService` moves `.png` to `resources/images/`, `FileCheckerService` fixes "Invalid date" frontmatter, `SpreadService` shifts over-cap notes, `BankruptcyService` below-threshold no-op, `getLastRunDate()` = today, `onStartup()` same-day idempotency.

#### ObsidianApplicationTests

`@Disabled` — context load is verified by the IT classes which run against a real DB. Kept in source to document the intent; re-enable by removing `@Disabled` and running with a live Postgres.

### Technology Notes — Testing

- **Testcontainers on CI**: GitHub-hosted ubuntu runners have Docker. `TESTCONTAINERS_RYUK_DISABLED=true` in CI workflow avoids permission issues with the Ryuk reaper container.
- **Spring context caching**: Spring Boot Test caches the application context per unique configuration. `NoteLifecycleIT` (has `@MockBean ChronoService`) and `ChronoServiceIT` (no mock) have different configurations → two separate containers start. Each test class gets its own Postgres container.
- **`@MockitoSettings(LENIENT)` scope**: applies only to the annotated class, not globally. The default `STRICT_STUBS` is preserved everywhere else.
- **`@TempDir` lifecycle**: `static @TempDir` lasts for the entire test class; instance `@TempDir` is recreated per test method. Integration tests use a static initializer instead (see above).

---

## Residual (next session)

- **HTTP resync endpoint** — optional `POST /admin/resync` to trigger delta sync without restart
- **Trash UI** — list and restore notes from `_trash/`
