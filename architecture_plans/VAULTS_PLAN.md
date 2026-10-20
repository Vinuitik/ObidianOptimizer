# Multi-vault (Design A: vault_id) — implementation plan

Status: APPROVED 2026-10-19 (D1–D8 below are final). Vault 1 = NewLife (`/vault`), all existing rows backfill to it.

## 0. Key facts
- Stored paths are ABSOLUTE container paths (`/vault/AI/...`). Vault 1 stays on `/vault`, others on `/vaults/<id>` → no path rewrite, no cross-vault path collisions. Exception: `workspace_items.filename` is relative → PK must include vault_id.
- No Flyway: each repo's `@PostConstruct initSchema()` does `CREATE/ALTER ... IF NOT EXISTS`. Python DDL too: `embedder/flashcards/generate.py:160` (cards), `embedder/failures.py:26`.
- Hardcoded: `embedder/ingest/placement.py:154` `LIKE '/vault/%%'`; `mcp_server.py:44` VAULT_DIR at import.
- `generate.py` writes cards with `ON CONFLICT (note_path, card_hash)` → cards key change must land Java+Python together.
- Live DB has unrelated PostGIS/tiger tables — leave alone.

## 1. Table inventory
PK/unique gains vault_id: notes(path), note_links(source_path,target_name), note_reviews(note_path), card_gen_attempts(note_path), sync_queue(path), workspace_items(filename), cards uq(note_path,card_hash), pending_image_jobs uq(note_path,image_path), note_chunks uq(note_path,source,chunk_index), scope_state(scope,type).
Filter column only (surrogate PK): card_flags, assignments, capture, tracks, track_item_groups, sources, feed_items, outbox_events, pipeline_failures (nullable). sources.url / feed_items.item_url uniques → (vault_id, url).
Inherit via FK: attempts→cards, track_items/track_schedule→tracks.
Global: bandit_arms, daily_capacity, consumed_events, app_settings (user prefs).
Per-vault settings → new `vault_settings(vault_id,key,value)`: syncEnabled, sync.drive.folder_id, sync.refresh_token, sync.account_email, syncPassphrase, sync.device_id, chronoLastRunDate, resourcePath, startupSyncMode. `vaultPath` → `vaults.container_path`. OAuth app creds stay global. Drive sync is SYMMETRIC: every vault can sync, each with its own folder_id + passphrase + device_id; enabling requires a folder_id unique across vaults. Phone PWA offline/mailbox works per vault.

## 2. Migration
- Pre-deploy: `linux_scripts/pg-backup.sh` (pg_dump -Fc → backups/pre-vaults-<ts>.dump) + restore smoke test into throwaway postgres, per-table count compare; abort on mismatch.
- `vaults/VaultRepository` @PostConstruct: `vaults(id BIGSERIAL, name, host_path, container_path UNIQUE, active, is_primary, archived, created_at)` + partial unique WHERE active; seed (1,'NewLife',HOST_VAULT_PATH,'/vault',true,true); setval.
- All repos `@DependsOn("vaultRepository")`. Per table: `ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)` (metadata-only, instant) → guarded `DO $$` PK/unique swap → every ON CONFLICT includes vault_id (CardRepository, NoteReviewRepository, SyncQueueRepository, PendingImageJobRepository, NoteChunkRepository, AssignmentRepository, TrackRepository, BanditService, generate.py).
- BM25: rebuild `idx_note_chunks_bm25` with vault_id if indexdef lacks it (verify pg_search version supports numeric filter field; else post-filter).
- Final: DROP DEFAULT on all vault_id so forgotten writes fail loudly.

## 3. Mounts (D1)
Opt 1: one `VAULTS_ROOT` parent mount — no recreate, but forces vault location and loses embedder ro+rw-subbind posture.
Opt 2 (rec): host-wrapper generates `docker-compose.vaults.yml` with per-vault binds mirroring vault 1's; one `compose up -d` at registration only (~20s); switching is instant.

## 4. Active vault
- Java: `vaults/ActiveVault` (AtomicReference, `switchTo(id)` → DB + `VaultSwitchedEvent`). ~30 `settingsRepo.getVaultPath()` call sites → `activeVault.root()`; remove `FileRepository.ROOT_FILE`. Repos take explicit `vaultId` (no ThreadLocal). `VaultHeaderFilter`: `X-Vault-Id` ≠ active → 409.
- Python: backend sends vault_id+root per embedder request; MCP resolves active vault per call (~5s cache); VAULT_DIR → function; placement.py → `vault_id=%s`.
- Workers: scanners (init sync, ChunkEmbeddingReconciler, ResourceScan, ImageScan, Chrono, SourcePoll, TodayPlan) = active vault only, rerun on switch. Queue consumers finish in their row's vault. SyncWorker + backup cron = every vault with syncEnabled (active or not).

## 5. Frontend
Settings → Vaults (list, register via HostFs browser + downtime confirm, set active, rename, sync toggle). Switcher in desktop + `pwa/TopBar.jsx` → PUT /api/vaults/active → full reload. API client sends `X-Vault-Id`; 409 → reload. IDB: vault 1 keeps `obsopt-offline`, others `obsopt-offline-v<id>`; active id in `obsopt-meta` (readable by sw.js). MEDIA_CACHE suffix `-v<id>`. Media URLs `/vault-media/<id>/` for new vaults.

## 6. Remove unsafe switch
Delete `FileRepository.updateVaultPath`, `NoteIndexRepository.forceResync`; SettingsController 400 on `vaultPath`; drop HostFsController PUT; host-wrapper delete `PUT /vault-path` + `_write_env_vault`, add `POST /vaults {id, host_path}` (validate, regen override, up -d, idempotent).

## 7. Milestones (one agent each; `./linux_scripts/test.sh <suites>`)
- M0 Safety: backup script + restore check; remove unsafe switch.
- M1 Registry + vault_id column backfill (java-it: old schema → boot → counts unchanged, all vault_id=1, idempotent reboot).
- M2 Key rewrite + ON CONFLICT (Java + generate.py) + BM25.
- M3 Java scoping: repos take vaultId, ActiveVault call sites, header filter (two-vault no-leak IT).
- M4 vault_settings + Drive gating + per-vault chrono + switch rescan.
- M5 Python scoping (embedder pytest, two tmp vaults).
- M6 Mounts: wrapper POST /vaults + override + nginx media.
- M7 Frontend: Vaults settings, switcher, header, IDB/SW scoping.
- M7b Vault GC chrono job (archived>15d purge; inactive-vault regenerable purge).
- M8 Hardening: DROP DEFAULT; full test.sh + live; manual E2E vs M0 dump counts.

## 8. Decisions
- D1 mounts: Opt 2.
- D2 Drive sync: symmetric for ALL vaults (own folder/passphrase each). Extra milestone M4b: per-vault Drive folders + PWA mailbox/export per vault.
- D3 feeds/sources/tracks: per-vault.
- D4 bandit_arms + daily_capacity: global.
- D5 workers: active vault only; in-flight jobs finish in own vault.
- D6 vault_id in PKs: yes.
- D7 delete vault: soft-hide (`archived_at`); vault folder on disk NEVER touched. Chrono GC job: archived >15 days and not restored → purge that vault's DB rows. Inactive (non-archived) vaults: no proactive cards/chunking/embedding; GC may purge regenerable data after N days (see open question G1).
- D8 ~20s downtime at registration: acceptable behind confirm.
