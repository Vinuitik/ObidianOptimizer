# Sync Domain Flows

Files: SyncController.java, SyncOAuthService.java, SyncService.java, SyncWorker.java, SyncQueueRepository.java, VaultEncryptionService.java, DriveService.java, DeviceIdentityService.java

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
Settings panel: paste OAuth client id/secret (from Google Cloud Console, type "Web
application", authorised redirect URI = <origin>/api/sync/oauth/callback) → Save
  → "Connect Google Drive" → GET /api/sync/oauth/url?origin=<window.origin>
      SyncOAuthService.buildAuthUrl(): state nonce (10-min TTL, single-use, in-memory)
      → browser → Google consent (access_type=offline&prompt=consent ⇒ refresh token)
  → GET /api/sync/oauth/callback?code&state
      handleCallback(): state check → code exchange (POST oauth2.googleapis.com/token)
      → app_settings sync.refresh_token + sync.account_email (Drive about())
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

`SyncWorker @Scheduled(cron)` → `SyncService.uploadPending()`:

```
syncQueueRepo.findByStatus("PENDING")
for each entry:
  readFile(vaultRoot + entry.path)          — UTF-8 string for .md, raw bytes for resources
  actualHash = sha256(plaintext)            — hash of what is ACTUALLY uploaded (not queue-time hash)
  VaultEncryptionService.encrypt(plaintext)
    → gzip(plaintext)
    → random 12B IV
    → AES-256-GCM encrypt(compressed)
    → [12B IV][ciphertext+tag]
  DriveService.uploadFile(relativePath, bytes, actualHash, deviceId, existingFileId)
    → ensureFolderPath (creates Drive folders, cached in folderIdCache)
    → files().update(existingFileId) if known, else files().create()
    → stores appProperties: {vault_path, content_hash, device_id, uploaded_at}
  syncQueueRepo.markDoneIfHashMatches(path, driveFileId, entry.contentHash)
    → conditional UPDATE (WHERE content_hash matches): if the note was edited
      mid-upload (row re-marked PENDING with a new hash), DONE is refused and
      the newer content uploads next pass — closes the lost-edit race
```

Drive file name: `<original-filename>.enc` (e.g. `note.md.enc`, `photo.png.enc`).  
To trigger immediately: `POST /api/sync/upload`  
To change schedule: `SYNC_UPLOAD_CRON` env var / `sync.upload.cron` property

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

Conflict rule: **PENDING local edits always win over Drive** until uploaded.
Files without pending edits are overwritten by Drive (Drive-wins for clean files).
Future per-file merge: compare `device_id` + `uploaded_at` in `appProperties`.

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
| `GET` | `/api/sync/status` | session | Queue counts, deviceId, enabled, mode (oauth/service-account/none), clientConfigured, connected, accountEmail, config flags |
| `POST` | `/api/sync/upload` | session | Immediately drain PENDING queue |
| `POST` | `/api/sync/download` | session | Pull all Drive files, write newer ones |
| `GET` | `/api/sync/oauth/url?origin=` | session | Google consent URL (Settings "Connect") |
| `GET` | `/api/sync/oauth/callback` | session | Code exchange; 302 → `/settings?drive=…` |
| `POST` | `/api/sync/disconnect` | session | Revoke + forget the Google connection |

---

## sync_queue Table

```sql
sync_queue(
  path           TEXT PRIMARY KEY,  -- vault-relative forward-slash path
  content_hash   TEXT NOT NULL,     -- SHA-256 of plaintext content
  status         TEXT,              -- PENDING / DONE / FAILED
  last_synced_at BIGINT,            -- epoch ms of last successful upload
  drive_file_id  TEXT,              -- Drive file ID for update-vs-create
  retry_count    INT                -- incremented on FAILED; reset to 0 on PENDING
)
```

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
- **Folder ID cache**: `DriveService.folderCache` is in-memory, cleared on restart. On restart the first upload to each folder makes 1–2 extra API calls to re-discover existing folder IDs.
- **Large resource files**: no chunking — a 100MB video is encrypted in-memory as a single byte[]. If this becomes a problem, split into chunks before encryption.
- **Scheduled upload only, no download**: `SyncWorker` auto-uploads but never auto-downloads. Download is manual (`POST /api/sync/download`). Add a second `@Scheduled` worker if you want auto pull.

---

## Change Index

| Thing to change | Where |
|---|---|
| Upload cron schedule | `SYNC_UPLOAD_CRON` env / `sync.upload.cron` property |
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
