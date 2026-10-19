# Vaults — FLOWS

Files: VaultRepository.java, ActiveVault.java, VaultMigrationIT.java (test); plan: architecture_plans/VAULTS_PLAN.md

Status: M1 only (registry + `vault_id` column backfill). Everything below marked [NOT IMPLEMENTED] is a later milestone.

## Boot: registry + backfill
Spring start → `VaultRepository.initSchema()` (@PostConstruct) → CREATE `vaults` + partial unique index `uq_vaults_single_active` → seed vault 1 (`ON CONFLICT DO NOTHING`) → `setval` on the id sequence
→ every vault-scoped repo (`@DependsOn("vaultRepository")`) runs its own `initSchema()` → `ALTER TABLE x ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)` (metadata-only; existing rows read as vault 1)
→ `ActiveVault.load()` reads the `active` row → `id()` / `root()`.
To change seed name/host path: `VaultRepository.initSchema()` / `deriveName()`; env `HOST_VAULT_PATH` (not currently passed to the backend container by docker-compose.yml, so host_path is NULL and name = 'NewLife' live).

## Tables that carry vault_id (M1: column only, no key/query change)
notes, note_links, note_reviews, card_gen_attempts, cards, card_flags, sync_queue, workspace_items, pending_image_jobs, note_chunks, scope_state, assignments, capture, tracks, track_item_groups, sources, feed_items, outbox_events, pipeline_failures (nullable).
Python-created: `cards` (embedder/flashcards/generate.py `ensure_schema`), `pipeline_failures` (embedder/failures.py) include the column in CREATE TABLE without FK; `CardRepository`/`PipelineFailureRepository` add the FK if missing (guarded DO block).
To add a table: add `ALTER ... vault_id` at the end of the owning repo's `initSchema()` + `@DependsOn("vaultRepository")`; extend the list in `VaultMigrationIT`.

## Not yet
- PK/unique/ON CONFLICT include vault_id; BM25 index rebuild [NOT IMPLEMENTED] (M2)
- Repos take vaultId, `ActiveVault.switchTo`, `VaultSwitchedEvent`, `X-Vault-Id` filter [NOT IMPLEMENTED] (M3)
- `vault_settings`, per-vault Drive sync/chrono [NOT IMPLEMENTED] (M4)
- Python scoping [NOT IMPLEMENTED] (M5); mounts/host-wrapper `POST /vaults` [NOT IMPLEMENTED] (M6); frontend [NOT IMPLEMENTED] (M7); GC job (M7b); DROP DEFAULT (M8) [NOT IMPLEMENTED]

## Technology Notes
- Schema management is `CREATE/ALTER ... IF NOT EXISTS` at bean init, no Flyway: no versioning, no rollback; bean order is the only ordering guarantee (hence `@DependsOn`). A repo missing `@DependsOn` can fail on a fresh DB (FK to a not-yet-created `vaults`).
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
| Migration test | `VaultMigrationIT` |
