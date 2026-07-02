# Sync Retention & Cleanup — keep Drive from filling up [NOT IMPLEMENTED]

Files (target): `sync/SyncService.java`, `sync/SyncWorker.java`, `sync/SyncQueueRepository.java`,
`sync/DriveService.java`, `stats/StatsController.java` (quota card), `frontend DashboardPage`.
Implemented baseline: `sync/FLOWS.md` (per-file AES-256-GCM Drive sync).

> Correction to the "multi-GB backups" framing first: the implemented sync is **not**
> snapshot zips. It is a per-file encrypted **mirror** — each vault file is one `.enc`
> on Drive, updated in place (`files().update` on the known `drive_file_id`). An update
> does NOT add a second multi-GB copy. What DOES grow without bound today:
>
> 1. **Orphans** — rename/move/soft-delete only touches the local queue
>    (`FLOWS.md: "No Drive-side delete"`); the old `.enc` stays on Drive forever.
>    Renaming a folder of videos = a full duplicate of those GB.
> 2. **Resources accumulate** — every yt-dlp download / captured PDF under `resources/`
>    is queued and uploaded, and nothing ever removes it.
> 3. **Revisions** — Drive keeps prior versions of an updated binary. These auto-purge
>    after ~30 days (or >100 revisions) unless pinned, so they're a bounded cost — and
>    actually a free 30-day undo history. Leave them alone.
>
> So "cleaning old backups" = **delete propagation + an orphan janitor + visibility**,
> not a backup-rotation scheme.

---

## Hard constraint to verify first: whose quota is it?

> **Update 2026-07-02:** OAuth sign-in shipped (Settings → Google Drive Sync,
> `SyncOAuthService`). When connected, files are owned by the USER's account —
> the user's real Drive quota applies and the service-account 15 GB problem
> disappears. The paragraph below now describes only the headless SA fallback.

Uploads via `GOOGLE_SERVICE_ACCOUNT_JSON` are **owned by the service account**, which has
its own ~15 GB quota — unless the sync root lives on a Shared Drive or the files are
created in a folder shared *to* the account (owner rules differ). If the vault is
multi-GB, quota exhaustion shows up as `storageQuotaExceeded` upload failures →
`FAILED` rows piling up in `sync_queue`. **Phase 0 = surface the number** (below) before
tuning anything else; every later phase reduces it.

---

## Phase 0 — Visibility (cheap, do first)

- `DriveService.about()` → `about.get(fields="storageQuota")` → bytes used/limit.
- Extend `GET /api/sync/status` with `{driveUsedBytes, driveLimitBytes, orphanCount?}`.
- Dashboard card next to the existing queue counts. A number you can see is a problem
  you notice before uploads start failing.

## Phase 1 — Delete propagation (stop making orphans)

Today `softDeleteNote`/`renameNote` just `syncQueueRepo.delete(oldRelPath)` — the local
bookkeeping forgets the file while Drive keeps it. Change to **tombstones**:

```
softDelete / rename(old) / moveNote(old)
  → sync_queue upsert {path: old, status: DELETE_PENDING}   (keep drive_file_id!)
SyncWorker.uploadPending() second pass:
  → findByStatus(DELETE_PENDING) → DriveService.deleteFile(driveFileId or lookup by
    appProperties vault_path) → remove row
```

- `deleteFile` = `files().delete(fileId)` → goes to Drive **trash** (30-day recovery),
  not hard delete. That grace period is our safety net for bugs — do NOT
  `emptyTrash()` programmatically.
- Rename keeps the new-path upload exactly as today; only the old path gains a tombstone.
- Edge: file deleted locally while a `PENDING` upload row exists → tombstone replaces it
  (same upsert), so we never upload-then-orphan.

## Phase 2 — Orphan janitor (clean the existing mess)

One-shot + weekly `@Scheduled` (`SYNC_JANITOR_CRON`, default Sunday night):

```
DriveService.listAllFiles()                       — already exists (download flow)
for each Drive file:
  vault_path appProperty → exists locally? (notes table hit OR Files.exists under vault)
  no local file AND no sync_queue row AND uploaded_at older than GRACE_DAYS (default 30)
    → files().delete(fileId)  (→ Drive trash)
report {scanned, deleted, freedBytes} → log + /api/sync/status lastJanitorRun
```

- The `GRACE_DAYS` guard prevents racing a mid-rename or a device that hasn't uploaded
  its new path yet; combined with Drive trash it makes the janitor double-recoverable.
- Manual trigger: `POST /api/sync/janitor` (same pattern as `/api/sync/upload`).
- Dry-run mode first (`?dryRun=true`) — list what WOULD be deleted; run it once by hand
  before trusting the cron.

## Phase 3 — Resource retention policy (the actual multi-GB lever)

Notes are KB; `resources/videos/` is where the GB live. Two options, pick at
implementation:

- **A (recommended): sync-exclude bulky media by default.** `SYNC_EXCLUDE_GLOBS`
  (default `resources/videos/**`) checked in `markPending` + `initialScan`. Rationale:
  a downloaded lecture is re-fetchable (`_reports/` + capture row keep the source URL);
  the irreplaceable data is the notes and images. Cuts Drive usage by ~the whole
  problem. Trade-off: a fresh machine restore doesn't bring videos back — acceptable,
  they re-download.
- **B: sync everything, rely on Phases 1–2 + CAPTURE_ARCH's source-trash lifecycle**
  (filed capture → source to `_trash/` → tombstone → Drive freed). Keeps full restore,
  keeps quota pressure.

A and B compose: ship Phase 1–2 regardless; decide A's default with real usage numbers
from Phase 0.

---

## Explicitly NOT doing (and why)

- **Snapshot/generation backups with rotation** ("keep last N weekly zips"): would
  re-upload multi-GB archives repeatedly — the thing the user is worried about. The
  mirror + Drive's native 30-day revisions + Drive trash already give point-in-time
  recovery for the window that matters. If long-term archival is ever wanted, that's a
  manifest + content-addressed blob design — a separate plan, not a tweak to this one.
- **Programmatic `emptyTrash` / hard delete**: the 30-day trash window is the last line
  of defense against a janitor bug eating the vault's cloud copy.

## Technology Notes (constraints / failure modes)

- **Service-account quota is the likely real limit** (~15 GB, not the user's own Drive
  plan). If Phase 0 shows the ceiling: move the sync root to a Shared Drive
  (`supportsAllDrives=true` on every Drive call) or switch to OAuth-as-user. Both are
  config/plumbing changes confined to `DriveService`.
- **Drive `files().delete` on a fileId the janitor mis-attributes** is the scary path —
  hence: appProperty match required, grace period, trash-not-purge, dry-run first.
- **`listAllFiles` is a full BFS** — fine weekly; don't put it in the upload cron.
- **Revisions**: auto-purge ≈30 days/100 revisions for binary uploads; do not set
  `keepRevisionForever` anywhere or updates start accumulating permanently.
- **Tombstones survive restarts** (sync_queue is DB-backed) — a missed worker pass just
  deletes later. Idempotent: deleting an already-trashed file returns 404 → treat as done.

## Change Index

| Thing to change | Where (planned) |
|---|---|
| Janitor schedule | `SYNC_JANITOR_CRON` env (new) |
| Orphan grace period | `SyncService.GRACE_DAYS` (new, default 30) |
| Sync exclusions | `SYNC_EXCLUDE_GLOBS` env (new) → checked in `markPending` + `initialScan` |
| Tombstone lifecycle | `SyncQueueRepository` (`DELETE_PENDING` status) + rename/softDelete call sites in `FileRepository` |
| Drive delete | `DriveService.deleteFile(fileId)` (new) |
| Quota readout | `DriveService.about()` (new) → `SyncController /status` → Dashboard card |
| Manual janitor + dry-run | `POST /api/sync/janitor?dryRun=` (new) |
| Shared-Drive support (if quota forces it) | `DriveService` — add `supportsAllDrives` to all calls |
