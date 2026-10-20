# Sync Domain Flows

Files: SyncController.java, SyncOAuthService.java, SyncService.java, SyncWorker.java, SyncQueueRepository.java, VaultEncryptionService.java, DriveService.java, DeviceIdentityService.java, DbBackupService.java

---

## Overview

Per-file encrypted sync to Google Drive. Every vault file (.md + resources/) is individually compressed, encrypted, and uploaded. Drive mirrors the vault folder structure as `path.enc` files with `appProperties` metadata that supports future conflict detection without downloading.

Configured **from the Settings page** (Google Drive Sync panel): sign in with Google
(OAuth), set the encryption passphrase, toggle scheduled uploads. Env vars remain a
headless bootstrap only.

---

## Auth & configuration (Settings-first)

Credential priority in `DriveService.ensureClient()` (client is built lazily, so
Settings edits apply without a restart):

```
1. OAuth (user signed in via Settings)        — files owned by YOUR account/quota
   app_settings: syncClientId + syncClientSecret + sync.refresh_token
2. Service account (headless fallback)        — GOOGLE_SERVICE_ACCOUNT_JSON env, 15GB SA quota
3. neither → sync disabled (isConfigured() = false)
```

### Connect flow (OAuth, scope `drive.file` — app-created files only)
```
.env GOOGLE_OAUTH_CLIENT_ID/SECRET (+ optional GOOGLE_OAUTH_REDIRECT_URI) seeded into
app_settings at boot (SettingsRepository.seedIfBlank — fills only while blank, UI wins
afterwards); or typed into the panel (fields auto-hide once set)
  → "Connect Google Drive" → GET /api/sync/oauth/url?origin=<window.origin>
      SyncOAuthService.buildAuthUrl(): redirect_uri = sync.oauth.redirect_uri setting
      if set, else <origin>/api/sync/oauth/callback; state nonce (10-min TTL, single-use)
      → browser → Google consent (access_type=offline&prompt=consent ⇒ refresh token)
  → GET /api/sync/oauth/callback?code&state
      handleCallback(): state check → code exchange (POST oauth2.googleapis.com/token)
      → app_settings sync.refresh_token + sync.account_email (Drive about())
      → CLEARS sync.drive.folder_id (an SA-era folder is invisible to drive.file —
        keeping it would 404 every upload; the app folder auto-creates instead)
      → driveService.reset() → 302 → /settings?drive=connected
Disconnect: POST /api/sync/disconnect → revoke (best-effort) + clear token/email.
```

- **Root folder**: `sync.drive.folder_id` setting (seeded from `GOOGLE_DRIVE_FOLDER_ID`
  env). Blank + OAuth mode → `DriveService.rootFolderId()` finds-or-creates a top-level
  **"ObsidianOptimizer"** folder and persists its id. Service-account mode requires an
  explicit folder id (the SA has no usable My Drive).
- **Passphrase**: `syncPassphrase` setting (seeded from `SYNC_PASSPHRASE` env); saving it
  in the UI calls `VaultEncryptionService.reload()` — no restart.
- **Enable toggle**: `syncEnabled` setting gates ONLY the scheduled cron
  (`SyncWorker.scheduledUpload`); the manual Sync now / Pull buttons work regardless.

---

## Queue Population

Three entry points all call `syncQueueRepo.markPending(relativePath, sha256)`:

```
FileRepository.createNote/updateNote/patchNote → markPending (after imageScanService.registerImages)
FileRepository.renameNote/moveNote             → delete(oldRelPath) + markPending(newRelPath)
FileRepository.renameNote backlink rewrites    → markPending for every source whose [[links]] were rewritten
FileRepository.softDeleteNote                  → delete(relPath)   — trashed files not synced
MediaController.uploadFile                     → markPending("resources/<subdir>/<filename>")
SyncService.initialScan()                      → scans notes table + resources/ BFS on startup
ChronoService.runAllJobs() hash loop           → markPending for chrono frontmatter rewrites + external edits
```

`markPending` is an idempotent upsert — safe to call on every write.  
Paths are vault-relative with forward slashes: `folder/note.md`, `resources/images/photo.png`.

---

## Upload Flow

Both triggers run on the **`sync` WorkerLane** (single-flight, off the caller thread):
`SyncWorker.scheduledUpload()` (cron) and `SyncWorker.triggerManualUpload()` (manual
button) → `lane.trigger(() -> SyncService.uploadPending())`. The manual REST endpoint
returns **202 immediately** — it never blocks on the drain. To change concurrency:
`SYNC_UPLOAD_CONCURRENCY` env (default 3).

```
uploadable = syncQueueRepo.findUploadable(maxRetries)   — PENDING ∪ (FAILED, retry_count < cap)
processTombstones()                                     — DELETE_PENDING → Drive trash (before uploads)
uploadRunning=true; uploadTotal=|uploadable|            — /sync/status progress snapshot
ExecutorService pool(SYNC_UPLOAD_CONCURRENCY, default 3):
  submit uploadOne(entry) per row, then await all; uploadDone counter ticks per task
  uploadOne(entry):
    readFile(vaultRoot + entry.path)        — UTF-8 for .md, raw bytes for resources
    actualHash = sha256(plaintext)          — hash of what is ACTUALLY uploaded (not queue-time hash)
    VaultEncryptionService.encrypt(plaintext) → gzip → 12B IV → AES-256-GCM → [IV][ct+tag]
    DriveService.uploadFile(relativePath, bytes, actualHash, deviceId, existingFileId)
      → ensureFolderPath → getOrCreateFolder (per-folder LOCK: concurrent uploads never
        double-create the same folder; cache hit is lock-free)
      → withRetry(...): files().update(existingFileId) or files().create(); exp backoff +
        jitter (0.5→8s, maxRetries attempts) on 429 / 5xx / 403-rate-limit
      → appProperties: {vault_path, content_hash, device_id, uploaded_at}
    syncQueueRepo.markDoneIfHashMatches(path, driveFileId, entry.contentHash)
      → conditional UPDATE (WHERE content_hash matches): note edited mid-upload → DONE
        refused, newer content uploads next pass (closes the lost-edit race)
    on Exception → syncQueueRepo.markFailed(path)  (retry_count++; re-tried next pass until cap)
```

Drive file name: `<original-filename>.enc` (e.g. `note.md.enc`, `photo.png.enc`).  
Progress while running: `GET /api/sync/status` → `{uploading, uploadDone, uploadTotal}`.  
To trigger immediately: `POST /api/sync/upload` (202, `{started}`)  
To change schedule: `SYNC_UPLOAD_CRON` env / `sync.upload.cron`; concurrency: `SYNC_UPLOAD_CONCURRENCY`

---

## Upload fast path — outbox + RabbitMQ (QUEUE_UNIFICATION_PLAN.md Phase 6)

`SyncQueueRepository.markPending` is the single funnel every producer goes through
(FileRepository create/update/patch/rename/move, MediaController.uploadFile,
ChronoService's hash loop, SyncService.initialScan) — so it's also the one place that
publishes to the outbox, no per-call-site hook needed:

```
markPending(path, hash)
    │
    ├─ UPSERT sync_queue (unchanged, same status-column source of truth)
    └─ OutboxRepository.enqueue("sync-upload", {path})
             │
        (same instant-publish + sweep-fallback mechanism as Phase 3 — see
         common/FLOWS.md "Phase 3 shape: the outbox")
             │
        RabbitMQ queue "sync-upload"
             │
  @RabbitListener SyncService.onSyncUploadMessage (concurrency = SYNC_UPLOAD_CONCURRENCY)
    → re-reads the row fresh (idempotency check: DONE/DELETE_PENDING/dead-lettered
      FAILED → no-op, someone else already resolved it)
    → SyncService.uploadOne(entry, vaultRoot, deviceId)   — SAME method the polling
      safety net calls per row; the only substantive upload/encrypt/mark logic in
      the codebase, reused by both paths
    success → ack (row is DONE)
    failure → uploadOne already called markFailed (retry_count++, exactly as before)
       retry_count < SYNC_UPLOAD_MAX_RETRIES → throw AmqpRejectAndDontRequeueException
           → nacked, routed via "sync-upload"'s DLX to "sync-upload-wait"
           → TTL sync.upload.retry-delay-ms (default 60s) → dead-lettered back to
             "sync-upload" → redelivered
       retry_count >= cap → just ack, drop — row stays FAILED (dead-lettered),
           same as the polling model's findUploadable() cap check
```

`SyncWorker.scheduledUpload()` is now the SAFETY NET only, cadence left UNCHANGED at
6h (unlike Group A's 15-60s→1h demotion) — sync's default was already coarse relative
to Group A's, and a missed-publish file only needs the poll to catch it once, not
frequently; see the comment on `scheduledUpload` for the full reasoning.

**Concurrency vs. DB backup/restore.** The polling drain and DB backup/restore always
shared one `WorkerLane` (single-threaded, so they could never overlap). The Rabbit
listener runs on its own consumer threads, outside that lane — a real new race, since
`pg_restore --clean` drops and recreates every table, `sync_queue` included.
`SyncService.dbBackupLock` (a `ReentrantReadWriteLock`) closes this: `SyncWorker`
wraps every `DbBackupService` call in `syncService.runExclusiveOfUploads(...)` (write
lock); `onSyncUploadMessage` takes the read lock for the span of one `uploadOne` call.
Multiple uploads still run concurrently with each other (read locks don't exclude
each other) — only a backup/restore gets true exclusivity. `DbBackupService.java`
itself is untouched; the lock is only taken at the `SyncWorker` call sites.

`SyncService.uploadsInFlight` (a `ConcurrentHashMap` key set) guards the other new
race: the polling batch loop and the Rabbit listener are now two independent paths
that could both reach the same row at once (previously impossible — only one drain
ever ran at a time). Whichever path loses the race is a no-op, not a double-upload.

To change the retry ladder's cadence: `SYNC_UPLOAD_RETRY_DELAY_MS` env /
`sync.upload.retry-delay-ms` (default 60000). Cap reuses the existing
`SYNC_UPLOAD_MAX_RETRIES` / `sync.upload.max-retries` — no new "how many retries"
knob. Queue names/DLX wiring: `RabbitQueueConfig.SYNC_UPLOAD_QUEUE` /
`SYNC_UPLOAD_WAIT_QUEUE`.

**Deliberately NOT migrated in Phase 6** (left exactly as before, on the shared
`WorkerLane` / plain `@Scheduled` cadence): tombstone processing
(`processTombstones`/`DELETE_PENDING`), the weekly janitor, DB backup/restore
internals, and the whole download direction (`downloadAll`/`downloadAllQuiet`) — none
of these are "local file changed, upload it" events, so they don't fit the
`markPending` chokepoint this phase hooks.

---

## Download Flow

`POST /api/sync/download` → `SyncService.downloadAll()`:

```
DriveService.listAllFiles()               — recursive BFS of Drive sync root
for each DriveFileInfo:
  validate vault_path appProperty         — resolve under vault root, reject traversal (../)
  computeLocalHash(absPath, relativePath) — SHA-256 of local file (empty string if missing)
  if contentHash matches Drive → skip
  if sync_queue row is PENDING → skip     — LOCAL WINS: a queued local edit hasn't
                                            reached Drive yet; overwriting it here
                                            would silently destroy it
  DriveService.downloadFile(fileId)       — byte[]
  VaultEncryptionService.decrypt(bytes)
    → AES-256-GCM decrypt (IV from first 12 bytes)
    → gunzip
  writeDownloaded(absPath, relativePath, plaintext):
    .md  → Files.writeString + noteIndex.upsert + noteLinkRepo.updateLinks + imageScanService.registerImages
    else → Files.write (raw bytes)
  syncQueueRepo.markSynced(path, driveContentHash, driveFileId)  — upsert DONE
    (upsert because files created on another device have no local row yet)
```

**Download retry (partial-tolerant).** Unlike uploads (which self-heal via the `sync_queue`
FAILED/retry_count loop), a download failure has no queue row to re-drive. So resilience lives
in two layers: (1) `DriveService.downloadFile` is now wrapped in `withRetry` — the SAME
transient policy as uploads (429/5xx/burst-403 → exp backoff), so a rate-limited bulk pull no
longer strands a file per-call; (2) `downloadAllQuiet` (restore path) collects per-file
failures and does `QUIET_RETRY_SWEEPS` (3) end-of-pass re-attempts, `QUIET_RETRY_PAUSE_MS`
(3s) apart, before giving up. Whatever's still failing is logged + counted in `downloadFailed`
(surfaced in `/progress` → the banner shows "N to retry"). `downloadAll` (manual pull) also
benefits from the `downloadFile` retry; its leftover failures simply have no DONE row, so the
next `POST /sync/download` re-pulls them. **The whole model is "partial is OK": the app is
usable while files stream in, and the banner tells the user content isn't 100% here yet.**

Conflict rule: **PENDING local edits always win over Drive** until uploaded, and
**DELETE_PENDING tombstones are never re-downloaded** (that would resurrect a deleted
file and cancel its tombstone). Files without pending edits are overwritten by Drive
(Drive-wins for clean files).
Future per-file merge: compare `device_id` + `uploaded_at` in `appProperties`.

---

## Delete Propagation & Janitor (retention)

**Tombstones** — local delete/rename now propagates to Drive:
```
FileRepository.softDeleteNote / renameNote(old) / moveNote(old)
  → syncQueueRepo.tombstone(rel):  row has drive_file_id → status=DELETE_PENDING
                                   never uploaded        → row just deleted
SyncService.uploadPending() (every pass, before uploads)
  → processTombstones(): DriveService.trashFile(id)  [Drive TRASH, 30-day recovery —
    files().delete would bypass trash, never use it] → row removed; failure ⇒ retried
```

**Janitor** — weekly sweep for orphans that predate tombstones (or external edits):
```
SyncWorker @Scheduled(sync.janitor.cron, default Sun 4am; gated on syncEnabled)
  → SyncService.janitor(dryRun): listAllFiles() → for each Drive file:
      local twin exists → keep | queue row PENDING → keep |
      uploaded_at within GRACE_DAYS (30) → keep (mid-rename race guard)
      else → trashFile() + drop queue row; report {scanned, orphans, freedBytes, paths}
Manual: POST /api/sync/janitor?dryRun=true (default true — report only)
Settings panel: "Check orphans" (dry run) → "Trash N orphan(s)" confirm button.
```
Version history/rollback is Drive-native: updated files keep prior revisions ~30 days
(auto-purged by Google — never set keepRevisionForever). Weekly cadence: set
`SYNC_UPLOAD_CRON=0 0 3 * * SUN`.

---

## DB Backup & Restore (`DbBackupService`)

The per-file sync covers vault FILES; this covers the **Postgres rows of ONE vault** — the expensive
derived data (embeddings in `note_chunks`, `cards`, image OCR, review state). So moving to a new
device doesn't re-run the GPU/LLM/vision pipeline. Since D1 each vault's Drive `_db/` folder holds
ONLY that vault's rows (no other vault's data leaks into its Drive). Logic lives in
`VaultDbSnapshotter` (catalog discovery + COPY); `DbBackupService` does Drive/encryption/status.
The full local `pg_dump` (`linux_scripts/pg-backup.sh`) is unchanged and is the all-vault backup.

**Table discovery** (`VaultDbSnapshotter.discover(vaultId)`, from the live catalog — no hand list):
```
scoped  = has a vault_id column                       → WHERE vault_id = <v>
        | no vault_id but single-col FK to a scoped table (attempts, track_items, track_schedule…)
                                                      → WHERE fk_col IN (SELECT ref FROM parent WHERE <parent pred>)  (OR over FKs, fixed point)
global  = everything else (app_settings, bandit_arms, daily_capacity, consumed_events, vaults…)
registry= global table FK-referenced by a scoped table (vaults) — exported, NEVER imported
extension-owned tables (spatial_ref_sys) excluded via pg_depend
```
To change X: `VaultDbSnapshotter.discover()`.

**Backup** (`SyncWorker.scheduledDbBackup` nightly / `triggerDbBackup` manual → sync lane):
```
DbBackupService.isPrimary(v)                — vaults.is_primary → globals included only if true
VaultDbSnapshotter.export(v, includeGlobals) — ONE REPEATABLE READ read-only tx; per table
                                              COPY (SELECT * FROM t WHERE <pred>) TO STDOUT → zip entry data/<t>.copy
                                              + manifest.json {format, vaultId, includesGlobals, tables[name,columns,rows]}
VaultEncryptionService.encrypt(v, zip)      — gzip+AES-GCM, that vault's passphrase
DriveService.uploadDbBackup(v, enc, "obsidian-db-v<id>-<ts>.vdb.enc", pgVersion, deviceId)
  → _db/ folder (EXCLUDED from listRecursive → janitor never trashes it)
prune()                                      — hard-delete dumps beyond SYNC_DBBACKUP_KEEP (3)
```

**Pre-delete backup (B1)** — `DbBackupService implements vaults.VaultBackup`: `VaultDeletionWorker` → `backup(v)` → `blockedReason(v)` (encryption? Drive?) → THROW if blocked, else `doBackup(v)` (same snapshot→encrypt→upload→prune as the nightly path; upload failure also throws). Nightly cron `backupNow(v)` → same `blockedReason` → WARN + skip. `VaultDeletionRepository.enqueue()` calls `blockedReason` too → `POST /vaults/{id}/delete` 409. To change: `DbBackupService.backup()` / `blockedReason()`.

**Device-local keys are never restored** (`VaultDbSnapshotter.DEVICE_LOCAL_VAULT_KEYS` / `DEVICE_LOCAL_GLOBAL_KEYS`): restore captures this device's rows first (`keepVault`/`keepGlobal`), runs the DELETE/COPY, then `reimpose()` drops whatever the backup brought for those keys and re-inserts the local ones (absent locally = absent after). Vault keys: `sync.refresh_token`, `sync.account_email`, `sync.device_id`, `syncPassphrase`, `sync.drive.folder_id`, `resourcePath`. Global (`app_settings`, primary restore only): `syncClientId`, `syncClientSecret`, `sync.oauth.redirect_uri`, `vaultPath`, and the legacy global copies of the five sync keys. Legacy `.pgdump.enc` path: `captureDeviceLocalAll()` before / `reapplyDeviceLocalAll()` after `pg_restore`.

**Restore** (`triggerDbRestore(force)` → sync lane; one-click "Restore from Drive"):
```
restoreBlockedReason(v, force) — synchronous 400 guard: passphrase set? Drive connected?
                                 THIS VAULT has notes (unless force)? a backup exists?
DriveService.latestDbBackup() → downloadFile → decrypt
name ends ".vdb.enc"  → VaultDbSnapshotter.restore(v, isPrimary, zip)   — ONE tx:
    manifest checks (format, vaultId == v) → vault registered here? → discover()
    capture this device's secrets (keepVault/keepGlobal)
    DELETE scoped rows of v, children first → (primary + snapshot has globals) DELETE globals
    COPY t (backup cols) FROM STDIN, parents first → setval() on serial columns past MAX(id)
    reimpose(): put THIS device's secrets back (see below)
    any exception → whole tx rolls back (vault untouched)
else (legacy ".pgdump.enc") → pg_restore --clean --if-exists --no-owner  [WHOLE DB, all vaults — old behaviour kept]
encryptionService.reload()                 — passphrase may live in restored vault_settings
syncService.downloadAllQuiet()             — write vault file bytes to disk, NO reprocessing
→ restart recommended (dbRestorePhase surfaces this in the UI)
```

Both run on the **same sync `WorkerLane`** as file uploads (single-flight — a backup/restore
never races a drain). Manual endpoints return 202; the Settings panel polls `/sync/status`
(`dbBackupRunning` / `dbRestoring` / `dbRestorePhase` / `dbBackup{exists,lastBackupAt,…}` / `dbEmpty` — per vault).

**Fresh-device bootstrap ordering** (passphrase + refresh token live in the DB you're restoring):
(1) register the vault on the new device (the snapshot refuses a vault id missing from `vaults`),
(2) OAuth-connect Drive fresh, (3) set the SAME passphrase (env `SYNC_PASSPHRASE` or Settings),
(4) Restore. Restoring the PRIMARY vault also replaces global tables (`app_settings`, `bandit_arms`, ...) EXCEPT the device-local keys above (refresh token, passphrase, OAuth client secret, device id, ... stay as on this device); restoring any other vault never touches globals.

---

## Encryption Detail

`VaultEncryptionService`:
- Key: `PBKDF2WithHmacSHA256(passphrase, "ObsidianSyncSalt", 310_000 iter)` → 256-bit AES key
- Passphrase source: `vault_settings.syncPassphrase` per vault (Settings UI), fallback env `sync.passphrase` for vault 1 only;
  `reload()` re-derives after a Settings save — no restart
- Fixed salt means any device with the same passphrase derives the same key — multi-device compatible
- Per-file IV: 12B random, prepended to ciphertext
- Wire format: `[12B IV][AES-GCM ciphertext + 16B auth tag]`
- GCM auth tag detects tampering or corruption on download

To rotate the key: change the passphrase in Settings — all existing Drive files become unreadable until re-uploaded.

---

## Device Identity

`DeviceIdentityService.getDeviceId()`:
- First call: enumerate network interfaces → first non-loopback MAC → SHA-256 → first 16 hex chars
- Fallback chain: hostname hash → "unknown-device" hash
- Stored in `vault_settings` (`sync.device_id`, per vault) after first computation; vault 1 = MAC hash, other vaults = hash(MAC#vaultId)
- Included as `device_id` in every uploaded file's Drive `appProperties`

---

## REST Endpoints

Controller maps `/sync/**` — nginx/Vite strip the `/api/` prefix, so the browser calls
`/api/sync/**`. (The original `@RequestMapping("/api/sync")` was unreachable through
the proxy — fixed 2026-07-02.)

| Method | Browser path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/sync/status` | session | Queue counts (`pendingCount`/`doneCount`/`failedCount`), live upload progress (`uploading`/`uploadDone`/`uploadTotal`), **download progress** (`downloading`/`downloadDone`/`downloadTotal`/`downloadFailed`), deviceId, enabled, mode, clientConfigured, connected, accountEmail, config flags, quota. **Does a Drive quota API call — do NOT poll it.** |
| `GET` | `/api/sync/progress` | session | **Cheap in-memory progress only** (upload + download + `dbRestoring`/`dbRestorePhase`/`dbBackupRunning`) — NO Drive calls. This is what the full-site `SyncBanner` polls every 3s while a pull/restore runs. |
| `POST` | `/api/sync/upload` | session | Kick the drain on the sync lane; **returns 202 immediately** (`{started}`) — poll `/status` for progress |
| `POST` | `/api/sync/download` | session | Pull all Drive files, write newer ones |
| `GET` | `/api/sync/oauth/url?origin=` | session | Google consent URL (Settings "Connect") |
| `GET` | `/api/sync/oauth/callback` | session | Code exchange; 302 → `/settings?drive=…` |
| `POST` | `/api/sync/disconnect` | session | Revoke + forget the Google connection |
| `POST` | `/api/sync/janitor?dryRun=` | session | Orphan sweep (dryRun=true default: report only) |
| `POST` | `/api/sync/backup` | session | **Full Backup** (UI): upload pending files THEN dump DB; **202**, one lane task |
| `POST` | `/api/sync/db/backup` | session | DB-only dump → encrypt → Drive `_db/`; **202** (nightly cron uses this path) |
| `POST` | `/api/sync/db/restore?force=` | session | Restore latest dump + vault files; **400** if blocked (not-empty/no-passphrase/no-backup), else **202** |

`/status` additionally returns `quota {usedBytes, limitBytes}` when connected (one
Drive API call — the panel fetches status on mount, don't poll it).

---

## sync_queue Table

```sql
sync_queue(
  path           TEXT PRIMARY KEY,  -- vault-relative forward-slash path
  content_hash   TEXT NOT NULL,     -- SHA-256 of plaintext content
  status         TEXT,              -- PENDING / DONE / FAILED / DELETE_PENDING
  last_synced_at BIGINT,            -- epoch ms of last successful upload
  drive_file_id  TEXT,              -- Drive file ID for update-vs-create
  retry_count    INT                -- incremented on FAILED; reset to 0 on PENDING
)
```

`findUploadable(maxRetries)` drives each pass: `PENDING` plus `FAILED` rows still under
the retry cap (self-healing — a transient Drive error no longer strands a file). Once
`retry_count` reaches `SYNC_UPLOAD_MAX_RETRIES` (default 5) a `FAILED` row is dead-lettered
(skipped) until the file is edited again (which resets it to PENDING).

---

## Technology Notes

- **OAuth scope is `drive.file`** — the app can only see/touch files it created. No
  "read all your Drive" consent screen, no collision with user files, and the by-name
  root-folder lookup is safe. Trade-off: files uploaded earlier by the SERVICE ACCOUNT
  are owned by the SA and invisible to the OAuth client — switching modes starts a
  fresh mirror (uploads fall through 404→create; old SA files become orphans).
- **Secrets live plaintext in `app_settings`** (client secret, refresh token,
  passphrase). Same trust domain as the plaintext vault on the same disk — acceptable
  for this single-user deployment; the passphrase protects data ON DRIVE, not locally.
- **Refresh tokens can die** (user revokes in Google account, or the OAuth client is
  in "Testing" publishing status — Google expires those tokens after ~7 days; set the
  client to "In production" to stop that). Failure mode: uploads mark FAILED,
  `/sync/status` still says connected — reconnect via Settings.
- **OAuth state nonce is in-memory, single-use, 10-min TTL** — a backend restart
  mid-consent aborts the flow; just click Connect again.
- **The redirect URI must be registered** on the OAuth client in Google Cloud Console:
  `<origin>/api/sync/oauth/callback` for every origin used (tunnel domain; add the
  localhost dev origin too if connecting from `npm run dev`).
- **Fixed PBKDF2 salt**: acceptable for a single-passphrase personal tool. If the codebase becomes public or multi-tenant, switch to a random salt stored in Drive (`.sync-salt` file) — all devices read it on first sync.
- **Drive `appProperties`**: app-only key-value metadata, not visible in Drive UI. Max 30 properties, 124B per key+value. `content_hash` enables hash comparison without downloading ciphertext.
- **No Drive-side delete**: renamed and soft-deleted files leave orphan `.enc` files on Drive. They're harmless but waste storage. Add `DriveService.deleteFile(fileId)` + call from rename/softDelete flows in V2.
- **Drive rate-limits sustained bulk creates**: a single Google Drive account starts
  returning `403 Forbidden` (often with an EMPTY reason — Google's edge throttle, not a
  parsed `userRateLimitExceeded`) once concurrent creates run too hot. Measured: at
  `SYNC_UPLOAD_CONCURRENCY=8` a large backfill degrades from partial to ~0% success as
  the account trips a cooldown; **3–4 is the safe ceiling** even with backoff. `withRetry`
  treats an unlabelled 403 as transient (backoff) and only excludes known-permanent
  reasons (`insufficientPermissions`, `storageQuotaExceeded`, …) — see `DriveService.isTransient`.
  If a big drain still stalls on 403s, lower concurrency and/or wait ~a few minutes for the
  per-user window to reset. Retries that exhaust the cap dead-letter the row (see sync_queue).
- **DB backup format is logical (`pg_dump -Fc`), not a volume tar**: the `postgres` service
  is `paradedb/paradedb:latest` (unpinned) — a physical PGDATA restore breaks on any PG-major
  bump; a logical dump restores across versions. Cost: `pg_restore` rebuilds indexes (incl.
  pgvector), a few minutes on restore. **The backend image pins `postgresql18-client`** — it
  MUST be ≥ the server major; bump it in the Dockerfile whenever the `postgres` image jumps major.
- **Restore is destructive + fresh-device oriented**: `pg_restore --clean` drops existing
  objects, so it's gated to an empty DB unless `force=true` (UI double-confirms). Running it
  under a live app works but a **restart is recommended** afterward (beans/caches read stale
  pre-restore rows); the UI says so via `dbRestorePhase`. Backups are FULL each night (147 MB
  DB → tens of MB compressed) — no incremental/WAL; fine at this size.
- **Per-vault snapshot (D1) failure modes**:
  - *Restore order / FKs*: scoped tables are ordered parents-first from `pg_constraint`; DELETE runs
    children-first, COPY parents-first. Only single-column FKs are followed; a multi-column FK to a
    scoped table makes the child look GLOBAL (it would then ride only in the primary's backup). FK
    cycles fall back to name order and may fail the restore (rolled back, not corrupting).
    Non-deferrable FKs are checked per COPY statement, so self-referencing tables are fine.
  - *Schema drift*: the manifest records the backup's columns; COPY names them explicitly. Columns
    ADDED since the backup take their defaults; a column REMOVED/renamed since the backup aborts the
    restore ("Schema drift") and rolls back. A table missing from the backup is left empty (WARN).
    No type-change handling — an incompatible type fails the COPY and rolls back.
  - *Id collisions*: surrogate-id (BIGSERIAL) tables restore with their old ids. If another vault
    has since taken one of those ids the restore fails on the PK and rolls back. UUID tables don't collide.
  - *Memory*: the snapshot is built in a `byte[]` (same order as the old `readAllBytes` of the dump).
  - *Consistency*: export is one REPEATABLE READ tx -> consistent across tables; it holds a snapshot
    (vacuum horizon) for the duration. Restore holds row locks on the vault's rows in one tx; the
    `dbBackupLock` keeps the upload pipeline out meanwhile.
  - *Legacy `.pgdump.enc`* still restores whole-DB via `pg_restore --clean` (ALL vaults overwritten) —
    needs the `postgresql18-client`. Once a new-format backup exists it is the newest and wins.
  - *Device-local keys survive restore*: a backup from device A restored on device B keeps B's Drive token/passphrase/device id/folder id/paths. Trade-off: the key list is hand-maintained (`DEVICE_LOCAL_*_KEYS`); a NEW per-device secret added to settings must be added there or a restore will overwrite it. `sync.drive.folder_id` is kept local, so a restore never re-points a vault at another Drive folder.
  - *Pre-delete backup is Drive-only*: no Drive/encryption = deletion refused (409) and the worker throws; there is no local-file fallback. The backup lands in the vault's own Drive `_db/` and is pruned to `sync.dbbackup.keep` (3) like any other — deleting a vault leaves its Drive folder untouched.
  - *Primary restore replaces globals* (`app_settings`, `bandit_arms`, ...) wholesale from the backup,
    never the `vaults` registry (device-specific host paths).
  - `postgresql` JDBC driver is now `compile` scope (CopyManager); `COPY ... TO STDOUT` needs no superuser/file access.
- **`_db/` is invisible to the file layer**: `DriveService.listRecursive` skips it, so the
  janitor never trashes dumps and `downloadAll`/`downloadAllQuiet` never treat a dump as a note.
- **Concurrency correctness**: uploads run on a fixed pool (`SYNC_UPLOAD_CONCURRENCY`).
  Per-row DB writes are connection-pool-safe. Folder creation is guarded by a per-key lock
  (`DriveService.folderLocks`) so two threads never double-create the same Drive folder.
- **Folder ID cache**: `DriveService.folderCache` is in-memory, cleared on restart. On restart the first upload to each folder makes 1–2 extra API calls to re-discover existing folder IDs.
- **Large resource files**: no chunking — a 100MB video is encrypted in-memory as a single byte[]. If this becomes a problem, split into chunks before encryption.
- **Scheduled upload only, no download**: `SyncWorker` auto-uploads but never auto-downloads. Download is manual (`POST /api/sync/download`) or part of restore (`downloadAllQuiet`). Add a second `@Scheduled` worker if you want auto pull. Note: a stranded download file is NOT auto-re-driven later (no queue row) — the in-pass retry sweeps + `downloadFile` `withRetry` are the safety net; a permanent failure needs a manual re-Sync.
- **`POST /sync/download` is synchronous (blocks the request thread)** — unlike upload/restore which run on the sync lane and return 202. Fine for a manual button, but a huge pull holds the connection open. Move it onto `syncWorker` if that bites. Restore's `downloadAllQuiet` already runs on the lane (async), so the app stays responsive during it.
- **`dbBackupLock` blocks Rabbit consumer threads for the FULL duration of a backup/
  restore** (a `ReentrantReadWriteLock`, not a queue-length-bounded wait) — a message
  stays unacked on the broker that whole time. `pg_dump`/`pg_restore` are "a few
  minutes" per the DB Backup section above, well under RabbitMQ's default 30-minute
  consumer-ack timeout, so this hasn't been an issue in testing — but if a restore
  ever grows much larger (or the DB does), that timeout is the thing that would fire
  first, requeuing the in-flight message.
- **The polling-vs-Rabbit double-upload guard (`uploadsInFlight`) is in-memory, not
  cross-instance** — fine for this single-backend-instance deployment; would need a
  DB-level lock (e.g. `pg_advisory_lock` on the path) if this ever ran with more than
  one backend replica.
- **Phase 6 was explicitly called out as the riskiest queue to migrate** (concurrency
  pool + tombstones + janitor + DB-backup piggyback, see QUEUE_UNIFICATION_PLAN.md) —
  only the upload direction (`markPending` → "sync-upload") was moved; tombstones,
  the janitor, DB backup/restore internals, and the whole download direction were
  deliberately left untouched (see "Upload fast path" above). Watch this one in
  production longer than Phase 4/5 before treating it as settled.

---

## Multi-vault notes (M4)
- Keys are derived lazily and cached per vault (`VaultEncryptionService.keys`); `reload(v)` after a passphrase change.
- The 3am DB backup is PER VAULT (D1): each sync-enabled vault's `_db/` gets only its own rows, encrypted with its passphrase; globals only in the primary's. Restore is per vault.
- PWA phone link + offline bundles + mailbox (`PwaController`, `OfflineExportService`, `MailboxConsumeService`) use the ACTIVE vault's folder/passphrase, captured once per call; true per-vault PWA is M4b/M7.
- `sync.janitor_last` is now written per vault (`vault_settings`).

## Change Index

| Thing to change | Where |
|---|---|
| Upload cron schedule | `SYNC_UPLOAD_CRON` env / `sync.upload.cron` property |
| Upload concurrency | `SYNC_UPLOAD_CONCURRENCY` env / `sync.upload.concurrency` (default 3) |
| DB backup schedule | `SYNC_DBBACKUP_CRON` env / `sync.dbbackup.cron` (default nightly 3am) |
| DB dumps retained | `SYNC_DBBACKUP_KEEP` env / `sync.dbbackup.keep` (default 3) |
| Backup / restore orchestration (Drive, encrypt, status) | `DbBackupService.backupNow()` / `restore()` |
| Which tables are scoped / global / registry, row predicate | `VaultDbSnapshotter.discover()` |
| COPY export / transactional per-vault import, sequence realign | `VaultDbSnapshotter.export()` / `restore()` / `realignSequences()` |
| Snapshot file name / legacy-dump detection | `DbBackupService.SNAPSHOT_SUFFIX` |
| Which vault carries globals | `DbBackupService.isPrimary()` (`vaults.is_primary`) |
| Restore guard (empty/force) | `DbBackupService.restoreBlockedReason()` |
| Quiet file materialization | `SyncService.downloadAllQuiet()` |
| Drive `_db/` ops + janitor skip | `DriveService.uploadDbBackup/listDbBackups/latestDbBackup`, `listRecursive` skip |
| pg client version | `obsidian/Dockerfile` `postgresql18-client` (match server major) |
| Upload retry cap (dead-letter) | `SYNC_UPLOAD_MAX_RETRIES` env / `sync.upload.max-retries` (default 5) |
| Upload fast path (outbox publish) | `SyncQueueRepository.markPending()` (single funnel — see "Upload fast path" above) |
| Upload fast path (consumer) | `SyncService.onSyncUploadMessage()` `@RabbitListener` |
| Upload retry ladder cadence | `SYNC_UPLOAD_RETRY_DELAY_MS` env / `sync.upload.retry-delay-ms` (default 60000) |
| Retry ladder queues (DLX/TTL) | `RabbitQueueConfig.SYNC_UPLOAD_QUEUE` / `SYNC_UPLOAD_WAIT_QUEUE` |
| Upload-vs-DB-backup mutual exclusion | `SyncService.dbBackupLock` / `runExclusiveOfUploads()`; taken at `SyncWorker`'s DbBackupService call sites |
| Cross-path double-upload guard | `SyncService.uploadsInFlight` |
| Which Drive errors retry | `DriveService.isTransient()` + `withRetry()` backoff |
| Folder double-create guard | `DriveService.folderLocks` (per-key lock in `getOrCreateFolder`) |
| Manual upload = async 202 | `SyncController.triggerUpload()` → `SyncWorker.triggerManualUpload()` |
| Live upload progress | `SyncService.uploadProgress()` → `/sync/status` |
| Live download progress | `SyncService.downloadProgress()` (`downloading`/`downloadDone`/`downloadTotal`/`downloadFailed`) → `/sync/status` + `/sync/progress` |
| Cheap progress poll (banner) | `SyncController.getProgress()` → `GET /sync/progress` (no Drive calls) |
| Download per-call retry | `DriveService.downloadFile()` (`withRetry`) |
| Restore download retry sweeps | `SyncService.QUIET_RETRY_SWEEPS` (3) / `QUIET_RETRY_PAUSE_MS` (3s) in `downloadAllQuiet` |
| Per-file quiet materialize | `SyncService.materializeQuiet()` |
| Uploadable selection (self-heal) | `SyncQueueRepository.findUploadable()` |
| Janitor schedule | `SYNC_JANITOR_CRON` env / `sync.janitor.cron` (default Sun 4am) |
| Janitor grace period | `SyncService.GRACE_DAYS` (30) |
| Tombstone lifecycle | `SyncQueueRepository.tombstone()` + `SyncService.processTombstones()` |
| OAuth redirect override | `.env GOOGLE_OAUTH_REDIRECT_URI` → `sync.oauth.redirect_uri` setting |
| Auto-sync on/off | Settings UI toggle → `vault_settings.syncEnabled` of the active vault (gates `SyncWorker` for that vault only) |
| Encryption passphrase | Settings UI → `vault_settings.syncPassphrase` of the active vault (env `SYNC_PASSPHRASE` seeds vault 1 only) |
| OAuth client id/secret | Settings UI → `app_settings.syncClientId` / `syncClientSecret` |
| Connected account | Settings UI Connect/Disconnect → `sync.refresh_token` / `sync.account_email` |
| Drive root folder | `app_settings sync.drive.folder_id` (env `GOOGLE_DRIVE_FOLDER_ID` seed; auto-created "ObsidianOptimizer" in OAuth mode) |
| Auto-created root folder name | `DriveService.DEFAULT_ROOT_NAME` |
| Service-account fallback | `GOOGLE_SERVICE_ACCOUNT_JSON` env var |
| OAuth scope / endpoints / state TTL | `SyncOAuthService` constants |
| Credential priority | `DriveService.ensureClient()` |
| PBKDF2 iterations | `VaultEncryptionService.PBKDF2_ITERATIONS` |
| PBKDF2 fixed salt | `VaultEncryptionService.PBKDF2_SALT` |
| Device ID computation | `DeviceIdentityService.computeDeviceId()` |
| Upload batch (PENDING → Drive) | `SyncService.uploadPending()` |
| Download logic (Drive → disk) | `SyncService.downloadAll()` |
| Upload race window (DONE refusal) | `SyncQueueRepository.markDoneIfHashMatches()` |
| Download bookkeeping (upsert DONE) | `SyncQueueRepository.markSynced()` |
| Local-wins download rule | `SyncService.downloadAll()` PENDING check |
| Drive path validation | `SyncService.downloadAll()` `target.startsWith(vaultRootPath)` |
| Initial vault scan | `SyncService.initialScan()` |
| Folder ID cache invalidation | `DriveService.folderCache.clear()` |
| Vault scoping (M3) | `SyncQueueRepository` methods take `long vaultId` first |
| Symmetric per-vault sync (M4) | Every sync-stack method takes `long vaultId` first: `SyncService.*`, `DriveService.*` (client per vault, `drives` map), `VaultEncryptionService.*` (key per vault), `DeviceIdentityService.getDeviceId(v)`, `DbBackupService.*`, `SyncOAuthService.*`. Root = `ActiveVault.rootFor(v)` |
| Which vaults sync | `SyncWorker.forEachSyncEnabledVault()` <- `SettingsRepository.syncEnabledVaultIds()` (syncEnabled=true, not archived; active or not). Manual endpoints/triggers (`SyncController`, `triggerManualUpload`, ...) act on the ACTIVE vault captured at call time |
| Per-vault Drive folder / token / passphrase / device id | `vault_settings` keys `sync.drive.folder_id`, `sync.refresh_token`, `syncPassphrase`, `sync.device_id` (see `vaults/FLOWS.md`) |
| Folder uniqueness (cross-vault push guard) | `SettingsRepository.setSyncDriveFolderId()` (throws), `SettingsController.updateSettings()` syncEnabled=true -> 400, runtime re-check in `DriveService.rootFolderId()` |
| Auto-created Drive root name | `DriveService.rootFolderId()`: vault 1 `ObsidianOptimizer`, others `ObsidianOptimizer-v<id>` |
| Rabbit sync-upload vault | `SyncService.onSyncUploadMessage()` reads `vaultId` from payload (absent = 1), skips if that vault's sync is off |
| OAuth connect target vault | `SyncOAuthService.buildAuthUrl(vaultId, origin)` stores it in the pending state; `handleCallback` writes the token to that vault |
| Pre-delete backup / enqueue gate | `DbBackupService.backup()`, `blockedReason()` |
| Which keys a restore preserves | `VaultDbSnapshotter.DEVICE_LOCAL_VAULT_KEYS`, `DEVICE_LOCAL_GLOBAL_KEYS` |
