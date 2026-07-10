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

The per-file sync covers vault FILES; this covers the **Postgres DB** — the expensive
derived data (embeddings in `note_chunks`, `cards`, image OCR, review state). So moving to
a new device doesn't re-run the GPU/LLM/vision pipeline. Runs `pg_dump`/`pg_restore` via the
`postgresql18-client` baked into the backend image.

**Backup** (`SyncWorker.scheduledDbBackup` nightly / `triggerDbBackup` manual → sync lane):
```
pg_dump -Fc -h postgres -U … -d obsidian  — ProcessBuilder + PGPASSWORD → temp file
  (no file drain — the 6h upload cron keeps Drive current; Sync first for a perfect snapshot)
VaultEncryptionService.encrypt(bytes)     — gzip+AES-GCM, same passphrase as file sync
DriveService.uploadDbBackup(enc, "obsidian-db-<ts>.pgdump.enc", pgVersion, deviceId)
  → _db/ folder (EXCLUDED from listRecursive → janitor never trashes it)
prune()                                    — hard-delete dumps beyond SYNC_DBBACKUP_KEEP (3)
```

**Restore** (`triggerDbRestore(force)` → sync lane; one-click "Restore from Drive"):
```
restoreBlockedReason(force)   — synchronous 400 guard: passphrase set? Drive connected?
                                DB empty (unless force)? a backup exists?
DriveService.latestDbBackup() → downloadFile → decrypt → temp file
pg_restore --clean --if-exists --no-owner -h postgres -U … -d obsidian <dump>
encryptionService.reload()                 — passphrase may live in the restored settings
syncService.downloadAllQuiet()             — write vault file bytes to disk, NO reprocessing
                                             (restored DB is authoritative; boot reconcile
                                              sees matching hashes → no re-embed/re-caption)
→ restart recommended (dbRestorePhase surfaces this in the UI)
```

Both run on the **same sync `WorkerLane`** as file uploads (single-flight — a backup/restore
never races a drain). Manual endpoints return 202; the Settings panel polls `/sync/status`
(`dbBackupRunning` / `dbRestoring` / `dbRestorePhase` / `dbBackup{exists,lastBackupAt,…}` / `dbEmpty`).

**Fresh-device bootstrap ordering** (chicken-and-egg — passphrase + refresh token live in
`app_settings` INSIDE the DB you're restoring): (1) OAuth-connect Drive fresh, (2) set the
SAME passphrase (env `SYNC_PASSPHRASE` or Settings), (3) Restore. The dump's `app_settings`
then overwrites those — fine because the passphrase must match anyway. If the dumped refresh
token is stale, reconnect Drive after restore.

---

## Encryption Detail

`VaultEncryptionService`:
- Key: `PBKDF2WithHmacSHA256(passphrase, "ObsidianSyncSalt", 310_000 iter)` → 256-bit AES key
- Passphrase source: `app_settings.syncPassphrase` (Settings UI), fallback env `sync.passphrase`;
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
- Stored in `app_settings` under `sync.device_id` after first computation
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
- **`_db/` is invisible to the file layer**: `DriveService.listRecursive` skips it, so the
  janitor never trashes dumps and `downloadAll`/`downloadAllQuiet` never treat a dump as a note.
- **Concurrency correctness**: uploads run on a fixed pool (`SYNC_UPLOAD_CONCURRENCY`).
  Per-row DB writes are connection-pool-safe. Folder creation is guarded by a per-key lock
  (`DriveService.folderLocks`) so two threads never double-create the same Drive folder.
- **Folder ID cache**: `DriveService.folderCache` is in-memory, cleared on restart. On restart the first upload to each folder makes 1–2 extra API calls to re-discover existing folder IDs.
- **Large resource files**: no chunking — a 100MB video is encrypted in-memory as a single byte[]. If this becomes a problem, split into chunks before encryption.
- **Scheduled upload only, no download**: `SyncWorker` auto-uploads but never auto-downloads. Download is manual (`POST /api/sync/download`) or part of restore (`downloadAllQuiet`). Add a second `@Scheduled` worker if you want auto pull. Note: a stranded download file is NOT auto-re-driven later (no queue row) — the in-pass retry sweeps + `downloadFile` `withRetry` are the safety net; a permanent failure needs a manual re-Sync.
- **`POST /sync/download` is synchronous (blocks the request thread)** — unlike upload/restore which run on the sync lane and return 202. Fine for a manual button, but a huge pull holds the connection open. Move it onto `syncWorker` if that bites. Restore's `downloadAllQuiet` already runs on the lane (async), so the app stays responsive during it.

---

## Change Index

| Thing to change | Where |
|---|---|
| Upload cron schedule | `SYNC_UPLOAD_CRON` env / `sync.upload.cron` property |
| Upload concurrency | `SYNC_UPLOAD_CONCURRENCY` env / `sync.upload.concurrency` (default 3) |
| DB backup schedule | `SYNC_DBBACKUP_CRON` env / `sync.dbbackup.cron` (default nightly 3am) |
| DB dumps retained | `SYNC_DBBACKUP_KEEP` env / `sync.dbbackup.keep` (default 3) |
| pg_dump / pg_restore logic | `DbBackupService.backupNow()` / `restore()` |
| Restore guard (empty/force) | `DbBackupService.restoreBlockedReason()` |
| Quiet file materialization | `SyncService.downloadAllQuiet()` |
| Drive `_db/` ops + janitor skip | `DriveService.uploadDbBackup/listDbBackups/latestDbBackup`, `listRecursive` skip |
| pg client version | `obsidian/Dockerfile` `postgresql18-client` (match server major) |
| Upload retry cap (dead-letter) | `SYNC_UPLOAD_MAX_RETRIES` env / `sync.upload.max-retries` (default 5) |
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
| Auto-sync on/off | Settings UI toggle → `app_settings.syncEnabled` (gates `SyncWorker` only) |
| Encryption passphrase | Settings UI → `app_settings.syncPassphrase` (env `SYNC_PASSPHRASE` = first-boot seed) |
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
