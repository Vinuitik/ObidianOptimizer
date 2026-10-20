# Vaults — FLOWS

Files: VaultRepository.java, ActiveVault.java, VaultInternalRoutingIT.java (test), ../internalapi/InternalAgentController.java, ../common/IngestClient.java, embedder/vault_paths.py, VaultController.java, VaultException.java, VaultSwitchedEvent.java, VaultKeys.java, VaultHeaderFilter.java, VaultMigrationIT.java + VaultScopingIT.java + VaultSwitchIT.java + VaultHeaderFilterTest.java (tests); settings: ../settings/SettingsRepository.java; sync: ../sync/SyncWorker.java; plan: architecture_plans/VAULTS_PLAN.md

Status: M1 (registry + `vault_id` column backfill) + M2 (keys + ON CONFLICT + BM25 include vault_id) + M3 (Java scoping: every vault-scoped query filters on `vault_id`, paths come from `ActiveVault`, `X-Vault-Id` filter) + M4 (`vault_settings`, symmetric per-vault Drive sync, per-vault chrono date, switching + rescan, registry REST) + M5 (Python scoping: embedder requests/callbacks carry the vault). Everything marked [NOT IMPLEMENTED] is a later milestone.

## Boot: registry + backfill
Spring start → `VaultRepository.initSchema()` (@PostConstruct) → CREATE `vaults` + partial unique index `uq_vaults_single_active` → seed vault 1 (`ON CONFLICT DO NOTHING`) → `setval` on the id sequence
→ every vault-scoped repo (`@DependsOn("vaultRepository")`) runs its own `initSchema()` → `ALTER TABLE x ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)` (metadata-only; existing rows read as vault 1)
→ `ActiveVault.load()` reads the `active` row → `id()` / `root()`.
To change seed name/host path: `VaultRepository.initSchema()` / `deriveName()`; env `HOST_VAULT_PATH` (not currently passed to the backend container by docker-compose.yml, so host_path is NULL and name = 'NewLife' live).

## Tables that carry vault_id (M1: column only, no key/query change)
notes, note_links, note_reviews, card_gen_attempts, cards, card_flags, sync_queue, workspace_items, pending_image_jobs, note_chunks, scope_state, assignments, capture, tracks, track_item_groups, sources, feed_items, outbox_events, pipeline_failures (nullable).
Python-created: `cards` (embedder/flashcards/generate.py `ensure_schema`), `pipeline_failures` (embedder/failures.py) include the column in CREATE TABLE without FK; `CardRepository`/`PipelineFailureRepository` add the FK if missing (guarded DO block).
To add a table: add `ALTER ... vault_id` at the end of the owning repo's `initSchema()` + `@DependsOn("vaultRepository")`; extend the list in `VaultMigrationIT`.

## Keys (M2)
Each repo's `initSchema()`, right after its `ADD COLUMN vault_id` → `VaultKeys.swapPrimaryKey / swapUnique / swapUniqueIndex` (one guarded `DO $$` = one txn per table; no-op once the key def contains vault_id).
PK (+vault_id, last col): notes(path), note_links, note_reviews, card_gen_attempts, sync_queue, workspace_items(filename), scope_state. UNIQUE: cards(note_path,card_hash,vault_id), pending_image_jobs(note_path,image_path,vault_id), index `uq_note_chunks_path_source_idx`(+vault_id), sources(vault_id,url), feed_items(vault_id,item_url).
No FK references any swapped key (all FKs target surrogate ids: attempts→cards.id etc.), so nothing dependent to rebuild.
Every `ON CONFLICT (...)` on those tables names vault_id (Java repos + `embedder/flashcards/generate.py _store`). `generate.ensure_schema` has its own guarded swap so the embedder booting first still has a valid arbiter. Inserts still omit vault_id → DEFAULT 1 (M3 passes it explicitly).
BM25: `NoteChunkRepository.initSchema()` creates/rebuilds `idx_note_chunks_bm25 (id, text, vault_id)` once (only if indexdef lacks vault_id); verified on `paradedb/paradedb:latest`. On a pg_search that rejects it, falls back to `(id, text)` with a WARN and M3 must post-filter.
Not touched (no vault in key): bandit_arms, daily_capacity, consumed_events, app_settings (global); capture/tracks/outbox/assignments (surrogate PK, `ON CONFLICT (id)` / `(track_id, capture_id)`); ON CONFLICT DO NOTHING without target (note_links, scope_state, daily_capacity).

## Scoping (M3)
Rule: no ThreadLocal, no hidden "current vault" inside a repository. Every method of a vault-scoped repo takes `long vaultId` FIRST (reads: `WHERE vault_id = ?`; writes: `INSERT ... vault_id`; `ON CONFLICT` already names it from M2).
- Request path: controller → `activeVault.id()` → service → repo. Services below controllers either take `vaultId` explicitly (`AssignmentService`, `ReviewService`, `FsrsStateWriter`, `ReviewPreparationService`, `FeedService`, `ImageScanService`, `EmbeddingService`, `SearchService`) or pin `ActiveVault` themselves (`FileRepository`, `SyncService`, `TodayPlanService`, `TrackProgressService`, `ChronoService`).
- Queue consumers: use the vault stored with the work. DB-row jobs carry it (`PendingImageJob.vaultId`, `Capture.vaultId`, `PendingChunk.vaultId`); Rabbit messages carry `"vaultId"` in the payload (`OutboxRepository.enqueue(vaultId, queue, payload)` adds it); a message without it is treated as vault 1 (queued before M3). Filesystem root of a job's vault: `ActiveVault.rootFor(vaultId)`.
- Scanners (startup sync in `FileRepository.init`, `ChunkEmbeddingReconciler`, `NoteEmbeddingWorker` poll, `ResourceScanService` retry, `ImageProcessingWorker` poll, `CardJobWorker`, `FlaggedCardRegenService.run`, `CaptureIngestWorker` worklists, `SourcePollWorker.drain`, `ChronoService.runAllJobs`, `TodayPlanService`) read `activeVault.id()` once per pass: active vault only, no proactive work for inactive vaults.
- `ActiveVault.root()` replaces `SettingsRepository.getVaultPath()` (removed) and `FileRepository.ROOT_FILE` (removed). `GET /settings` reports `ActiveVault.root()` as `vaultPath`.
- By-surrogate-id ops that expose data take the vault too (`TrackRepository.get/getItem/delete`, `SourceRepository.get/delete`, `FeedRepository.get`, `AssignmentRepository.findCard/findAssignment`, `CardRepository.flag`). Pure id-keyed bookkeeping on a row the caller already fetched (e.g. `CaptureRepository.updateStatus(id)`, `TrackRepository.completeItem(id)`) is NOT re-checked.
- `VaultHeaderFilter` (servlet filter, every path except `/api/internal/**`): header `X-Vault-Id` present and != `ActiveVault.id()` (or non-numeric) → `409 {"error":"vault_mismatch","activeVaultId":N,"requestedVaultId":M}`; header absent → allowed (frontend sends it from M7). To change: `VaultHeaderFilter.shouldNotFilter()` / `doFilterInternal()`.
- BM25: the `idx_note_chunks_bm25 (id, text, vault_id)` index from M2 is queried with an ordinary `AND vault_id = ?` predicate next to `@@@ paradedb.match(...)` (`NoteChunkRepository.findByTextSearch`); correct whether or not pg_search pushes the filter into the index.
- Tests: `VaultScopingIT` (two temp-dir vaults, row 2 inserted, `ActiveVault.set(id, root)`): notes, review queue, FSRS rows, BM25 + vector search, embed queue, image jobs, cards/assignments, tracks/sources/feed/capture/workspace never leak; `VaultHeaderFilterTest` (409). ITs that use a temp vault must pin `activeVault.set(1L, VAULT)` (the registry row says `/vault`); unit tests use `ActiveVault.forTest(id, root)`.

## Per-vault settings (M4)
`vault_settings(vault_id, key, value, PK(vault_id,key))`, created in `SettingsRepository.initSchema()` (`@DependsOn("vaultRepository")`).
Per-vault keys (`SettingsRepository.PER_VAULT_KEYS`): syncEnabled, sync.drive.folder_id, sync.refresh_token, sync.account_email, syncPassphrase, sync.device_id, chronoLastRunDate, resourcePath, startupSyncMode. Every accessor takes `long vaultId` first (`getSyncPassphrase(v)`, `isSyncEnabled(v)`, `getChronoLastRunDate(v)`, ...; generic `getForVault/setForVault`).
Boot migration: `INSERT ... SELECT 1, key, value FROM app_settings WHERE key = ANY(PER_VAULT_KEYS) ON CONFLICT DO NOTHING` — copies to vault 1, idempotent, never overwrites a later edit; the `app_settings` rows stay but are never read. Env seeds (`SYNC_PASSPHRASE`, `GOOGLE_DRIVE_FOLDER_ID`) now seed vault 1 in `vault_settings`.
Global (stay in `app_settings`): Google OAuth app creds (syncClientId/Secret, redirect uri), review caps, flags, everything else.
New vaults (id != 1): no rows -> defaults in the accessors (`syncEnabled=false`, `startupSyncMode=blocking`, `resourcePath=<container_path>/resources/images`, others blank).

## Switching (M4)
REST (nginx strips `/api`): `GET /vaults`; `PUT /vaults/active {id}` (404 unknown, 409 archived); `PATCH /vaults/{id} {name}`; `POST /vaults/{id}/archive` + `/restore` (409 on the active or last non-archived vault). `VaultController` -> `ActiveVault.switchTo(id)` / `VaultRepository`. `/vaults/**` bypasses `VaultHeaderFilter` (a stale tab must still be able to switch). Registration (POST /vaults) is M6 [NOT IMPLEMENTED].
`ActiveVault.switchTo(id)` -> `VaultRepository.activate(id)` (one txn: clear `active`, set it; validates 404/409) -> AtomicReference flips -> `VaultSwitchedEvent(old,new)` (no event when id == current).
Listeners rerun for the new vault: `FileRepository.onVaultSwitched` (startup disk sync + image scan, async), `ChunkEmbeddingReconciler`, `ResourceScanService`, `ImageProcessingWorker` (each kicks its own tick; their `claimBatch` reads `activeVault.id()` per pass). `SyncService.initialScan` is NOT rerun on switch (it runs at boot for every sync-enabled vault + the active one).
Inactive vaults: no scanners, no card gen/chunking/embedding (D5). In-flight queue work finishes in its own vault (`rootFor(vaultId)`).
To change what reruns on a switch: add/remove an `@EventListener` on `VaultSwitchedEvent`.

## Embedder scoping (M5)
Java → embedder: every request that touches vault files/DB carries `vault_id` + `root` (`ActiveVault.rootFor(vaultId)`): `IngestClient.scoped(vaultId, payload)` (`submitInPlace/submitStandalone/submitText/resume(vaultId, …)`, `/placement/group`), `CardGenerationService` (generate/regenerate), `TrackAgentClient.submitMinicourse(vaultId, trackId)`, `CaptureController.download`. The vault is the ROW's (`Capture.vaultId`, the note's vault), not necessarily the active one. `/embed`, `/flashcards/roll|judge`, `/playlist/expand`, `/subs`, `/subscriptions/discover` touch no vault state → unscoped.
Embedder → Java (`/api/internal/**`): bodies carry `vaultId` (GET tracks: `?vaultId=`; absent = vault 1) → `InternalAgentController.vaultOf()` → `FileRepository.createNote/updateNote(vaultId, …)`, `MediaController.storeResourceBytes(vaultId, …)`, `captureRepo.create(vaultId, …)`, `trackRepo.*(vaultId, …)`, `resolveUnderVault(vaultId, rel)` (root = `rootFor(vaultId)`, escape-guarded). Never `ActiveVault.id()/root()` — an ingest finishing after a switch writes into its own vault. `SyncService` was already vault-explicit (M4).
Embedder internals: `embedder/vault_paths.py` + `embedder/ingest/FLOWS.md` (Multi-vault scoping rows). MCP + `/pdf-page` + `/media-rendition` resolve the ACTIVE vault per call (~5s cache), `placement` uses `vault_id = %s` instead of `LIKE '/vault/%%'`.
Tests: `VaultInternalRoutingIT` (callback after switch, no-vaultId = vault 1, escape guard), `embedder/tests/test_multivault.py`.

## Not yet
- mounts/host-wrapper `POST /vaults` [NOT IMPLEMENTED] (M6) — the embedder already computes `<root>/_reports|_workspace|resources` for non-1 vaults but nothing mounts them rw yet; frontend [NOT IMPLEMENTED] (M7); GC job (M7b); DROP DEFAULT (M8) [NOT IMPLEMENTED]

## Technology Notes
- Schema management is `CREATE/ALTER ... IF NOT EXISTS` at bean init, no Flyway: no versioning, no rollback; bean order is the only ordering guarantee (hence `@DependsOn`). A repo missing `@DependsOn` can fail on a fresh DB (FK to a not-yet-created `vaults`).
- Key swaps take ACCESS EXCLUSIVE and rebuild the table's index (seconds on current sizes; note_chunks/BM25 rebuild is the slowest). Swapping a PK where some other table later adds an FK to it would fail the DROP; keep FKs on surrogate ids. A key with vault_id LAST keeps existing path lookups on the index; M3 queries filter both anyway.
- M3 scoping is enforced by code, not the DB: a repo method that forgets `vault_id` on an INSERT still lands in vault 1 until M8 drops the DEFAULT. `VaultScopingIT` is the only guard — add a case there when adding a vault-scoped query.
- `DEFAULT 1` is a safety net: any insert that forgets vault_id lands in vault 1 silently. It is removed only in M8; until then wrong-vault writes are NOT caught.
- `ADD COLUMN ... NOT NULL DEFAULT 1` on Postgres 11+ is metadata-only (instant), but takes a brief ACCESS EXCLUSIVE lock per table.
- `ActiveVault` is an in-memory AtomicReference, single backend node; loaded at boot, changed only by `switchTo` (DB flag is the source of truth, a second backend node would not see a switch). Scanners read it once per pass, so a switch mid-pass finishes the pass in the old vault's rows (by design) — but `FileRepository`, `TodayPlanService` and other `ActiveVault`-pinning services re-read it per call and follow a switch immediately.
- `switchTo` is `synchronized` and the DB update commits before the in-memory flip; if the process dies in between, the next boot loads the DB flag, so the DB wins.
- Dropping a vault's `syncEnabled` / archiving only stops SyncWorker/backup from picking it; manual endpoints still act on the ACTIVE vault regardless.
- The nightly DB backup is a whole-database `pg_dump` (every vault's rows) encrypted with, and uploaded to, EACH sync-enabled vault's own folder — a vault's Drive folder therefore holds a copy of the other vaults' data, encrypted under that vault's passphrase. Restore (`DbBackupService.restore(v, force)`) is whole-DB too.
- `FileRepository`'s no-vault methods are still an active-vault facade (UI request path). Queue consumers / embedder callbacks MUST use the `(long vaultId, …)` overloads (`createNote`, `updateNote`); calling the facade from a job writes to the vault active NOW.
- Embedder side: a job's vault is fixed at submit time and lives in the in-memory job dict (lost on embedder restart, like the job itself; the Java capture row keeps `vault_id` and resubmits with it). `active()` for MCP is a 5s cache, so a tool call right after a switch can still see the previous vault for up to 5s. A request with a non-1 `vault_id` but no `root` is rejected (422) rather than guessed.
- `escalation.FIXES_DIR` (agent fix-log) is still the global `AGENT_FIXES_DIR`, not per vault (debug log, no vault data).
- Rabbit payloads without `vaultId` (queued before the M3 deploy) are treated as vault 1.
- Seed hardcodes id 1 and container_path `/vault` (must match the compose mount). If `/vault` is changed, the UNIQUE(container_path) seed insert for id 1 is skipped when a row already exists, silently keeping the old value.
- Only one `active` row is allowed (partial unique index); the seed is `ON CONFLICT DO NOTHING`, so an install that already has some other active vault keeps it.

## Change Index
| Thing | Where |
|---|---|
| vaults DDL / seed / name derivation | `VaultRepository.initSchema()`, `deriveName()` |
| Seed host path | env `HOST_VAULT_PATH` |
| Active vault id/root | `ActiveVault.load()`, `id()`, `root()` |
| vault_id column per table | each repo's `initSchema()` (end of method) |
| Python-side column | `embedder/flashcards/generate.py ensure_schema`, `embedder/failures.py` |
| Key swap helpers | `VaultKeys.swapPrimaryKey/swapUnique/swapUniqueIndex` (call sites: each repo's `initSchema()`) |
| BM25 index def | `NoteChunkRepository.initSchema()` |
| Python cards key + upsert | `generate.ensure_schema`, `generate._store` |
| Migration test | `VaultMigrationIT` |
| Vault-scoping tests | `VaultScopingIT`, `VaultHeaderFilterTest` |
| Add vault scoping to a query | give the repo method `long vaultId` first, `WHERE vault_id = ?` / insert column; controller passes `activeVault.id()`; queue consumer reads the row/message vault |
| 409 on vault mismatch | `VaultHeaderFilter` |
| Job's vault filesystem root | `ActiveVault.rootFor(vaultId)` |
| Rabbit payload carries vaultId | `OutboxRepository.enqueue(vaultId, queue, payload)` |
| Test helpers | `ActiveVault.set(id, root)`, `ActiveVault.forTest(id, root)` |
| Embedder request carries vault | `IngestClient.scoped(vaultId, payload)`; `CardGenerationService`, `TrackAgentClient.submitMinicourse`, `CaptureController.download` (add `vault_id`/`root`) |
| Embedder callback routes by vault | `InternalAgentController.vaultOf()`, `FileRepository.createNote/updateNote(long vaultId, …)`, `MediaController.storeResourceBytes(long vaultId, …)` |
| Embedder-side vault object / paths | `embedder/vault_paths.py` (`Vault`, `active()`); per-endpoint `main._vault_of(req)` |
| M5 tests | `VaultInternalRoutingIT`, `embedder/tests/test_multivault.py` |
| Per-vault setting keys | `SettingsRepository.PER_VAULT_KEYS`, `getForVault/setForVault` |
| vault_settings DDL + legacy copy | `SettingsRepository.initSchema()` |
| Switch vault | `ActiveVault.switchTo()`, `VaultRepository.activate()`, `VaultController.setActive()` |
| What reruns on a switch | `@EventListener VaultSwitchedEvent` in `FileRepository`, `ChunkEmbeddingReconciler`, `ResourceScanService`, `ImageProcessingWorker` |
| Archive / restore rules | `VaultRepository.archive()/restore()` |
| Vault REST | `VaultController` |
| M4 tests | `VaultSwitchIT`, `SyncWorkerTest` |
