# Sync Domain Flows

Files: SyncController.java, SyncService.java, SyncWorker.java, SyncQueueRepository.java, VaultEncryptionService.java, DriveService.java, DeviceIdentityService.java

---

## Overview

Per-file encrypted sync to Google Drive. Every vault file (.md + resources/) is individually compressed, encrypted, and uploaded. Drive mirrors the vault folder structure as `path.enc` files with `appProperties` metadata that supports future conflict detection without downloading.

---

## Queue Population

Three entry points all call `syncQueueRepo.markPending(relativePath, sha256)`:

```
FileRepository.createNote/updateNote/patchNote → markPending (after imageScanService.registerImages)
FileRepository.renameNote/moveNote             → delete(oldRelPath) + markPending(newRelPath)
FileRepository.softDeleteNote                  → delete(relPath)   — trashed files not synced
MediaController.uploadFile                     → markPending("resources/<subdir>/<filename>")
SyncService.initialScan()                      → scans notes table + resources/ BFS on startup
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
  VaultEncryptionService.encrypt(plaintext)
    → gzip(plaintext)
    → random 12B IV
    → AES-256-GCM encrypt(compressed)
    → [12B IV][ciphertext+tag]
  DriveService.uploadFile(relativePath, bytes, contentHash, deviceId, existingFileId)
    → ensureFolderPath (creates Drive folders, cached in folderIdCache)
    → files().update(existingFileId) if known, else files().create()
    → stores appProperties: {vault_path, content_hash, device_id, uploaded_at}
  syncQueueRepo.markDone(path, driveFileId)
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
  computeLocalHash(absPath, relativePath) — SHA-256 of local file (empty string if missing)
  if contentHash matches Drive → skip
  DriveService.downloadFile(fileId)       — byte[]
  VaultEncryptionService.decrypt(bytes)
    → AES-256-GCM decrypt (IV from first 12 bytes)
    → gunzip
  writeDownloaded(absPath, relativePath, plaintext):
    .md  → Files.writeString + noteIndex.upsert + noteLinkRepo.updateLinks + imageScanService.registerImages
    else → Files.write (raw bytes)
  syncQueueRepo.markDone(path, driveFileId)
```

Download is last-write-wins with no conflict resolution (V1 scope).  
Future conflict detection: compare `device_id` + `content_hash` in `appProperties` before downloading.

---

## Encryption Detail

`VaultEncryptionService`:
- Key: `PBKDF2WithHmacSHA256(passphrase, "ObsidianSyncSalt", 310_000 iter)` → 256-bit AES key
- Fixed salt means any device with the same passphrase derives the same key — multi-device compatible
- Per-file IV: 12B random, prepended to ciphertext
- Wire format: `[12B IV][AES-GCM ciphertext + 16B auth tag]`
- GCM auth tag detects tampering or corruption on download

To rotate the key: change `SYNC_PASSPHRASE` — all existing Drive files become unreadable until re-uploaded.

---

## Device Identity

`DeviceIdentityService.getDeviceId()`:
- First call: enumerate network interfaces → first non-loopback MAC → SHA-256 → first 16 hex chars
- Fallback chain: hostname hash → "unknown-device" hash
- Stored in `app_settings` under `sync.device_id` after first computation
- Included as `device_id` in every uploaded file's Drive `appProperties`

---

## REST Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/sync/status` | session | Queue counts, deviceId, config flags |
| `POST` | `/api/sync/upload` | session | Immediately drain PENDING queue |
| `POST` | `/api/sync/download` | session | Pull all Drive files, write newer ones |

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
| Encryption passphrase | `SYNC_PASSPHRASE` env var |
| Drive root folder | `GOOGLE_DRIVE_FOLDER_ID` env var |
| Drive credentials | `GOOGLE_SERVICE_ACCOUNT_JSON` env var |
| PBKDF2 iterations | `VaultEncryptionService.PBKDF2_ITERATIONS` |
| PBKDF2 fixed salt | `VaultEncryptionService.PBKDF2_SALT` |
| Device ID computation | `DeviceIdentityService.computeDeviceId()` |
| Upload batch (PENDING → Drive) | `SyncService.uploadPending()` |
| Download logic (Drive → disk) | `SyncService.downloadAll()` |
| Initial vault scan | `SyncService.initialScan()` |
| Folder ID cache invalidation | `DriveService.folderCache.clear()` |
