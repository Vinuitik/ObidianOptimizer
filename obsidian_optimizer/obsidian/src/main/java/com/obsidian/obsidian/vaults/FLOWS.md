# Vaults — FLOWS

Files: VaultRepository.java, ActiveVault.java, VaultKeys.java, VaultMigrationIT.java (test); plan: architecture_plans/VAULTS_PLAN.md

Status: M1 (registry + `vault_id` column backfill) + M2 (keys + ON CONFLICT + BM25 include vault_id). Queries still unfiltered (M3). Everything below marked [NOT IMPLEMENTED] is a later milestone.

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

## Not yet
- Repos take vaultId, `ActiveVault.switchTo`, `VaultSwitchedEvent`, `X-Vault-Id` filter [NOT IMPLEMENTED] (M3)
- `vault_settings`, per-vault Drive sync/chrono [NOT IMPLEMENTED] (M4)
- Python scoping [NOT IMPLEMENTED] (M5); mounts/host-wrapper `POST /vaults` [NOT IMPLEMENTED] (M6); frontend [NOT IMPLEMENTED] (M7); GC job (M7b); DROP DEFAULT (M8) [NOT IMPLEMENTED]

## Technology Notes
- Schema management is `CREATE/ALTER ... IF NOT EXISTS` at bean init, no Flyway: no versioning, no rollback; bean order is the only ordering guarantee (hence `@DependsOn`). A repo missing `@DependsOn` can fail on a fresh DB (FK to a not-yet-created `vaults`).
- Key swaps take ACCESS EXCLUSIVE and rebuild the table's index (seconds on current sizes; note_chunks/BM25 rebuild is the slowest). Swapping a PK where some other table later adds an FK to it would fail the DROP; keep FKs on surrogate ids. A key with vault_id LAST keeps existing path lookups on the index; M3 queries filter both anyway.
- `DEFAULT 1` is a safety net: any insert that forgets vault_id lands in vault 1 silently. It is removed only in M8; until then wrong-vault writes are NOT caught.
- `ADD COLUMN ... NOT NULL DEFAULT 1` on Postgres 11+ is metadata-only (instant), but takes a brief ACCESS EXCLUSIVE lock per table.
- `ActiveVault` is an in-memory AtomicReference, single backend node; it is loaded once at boot and never refreshed in M1.
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
