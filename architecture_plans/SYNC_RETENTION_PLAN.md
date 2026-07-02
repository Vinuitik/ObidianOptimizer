# Sync Retention & Cleanup — keep Drive from filling up
# STATUS: Part A + Phases 0–2 IMPLEMENTED 2026-07-03 (see sync/FLOWS.md "Delete
# Propagation & Janitor"). Phase 3 (resource sync-exclusion) remains open — decide
# with real quota numbers from the new /sync/status readout.

Files (target): `sync/SyncService.java`, `sync/SyncWorker.java`, `sync/SyncQueueRepository.java`,
`sync/DriveService.java`, `sync/SyncOAuthService.java`, `settings/SettingsRepository.java`,
`docker-compose.yml`, `frontend SettingsPage DriveSyncPanel`.
Implemented baseline: `sync/FLOWS.md` (per-file AES-256-GCM Drive sync + OAuth sign-in).

---

## Part A — env-first config, hide-when-set (approved direction 2026-07-02)

Root cause of the dead Connect button: the user's OAuth credentials live in `.env`
(`GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` / `GOOGLE_OAUTH_REDIRECT_URI`)
but nothing reads those vars — only `SYNC_PASSPHRASE` and `GOOGLE_DRIVE_FOLDER_ID` were
seeded. Fix = envs become first-class seeds, and the UI stops showing fields that are
already satisfied.

1. **Compose passthrough + seeds.** Add the three `GOOGLE_OAUTH_*` vars to the backend
   `environment:` block. `SettingsRepository`: seed `syncClientId`/`syncClientSecret`
   from them. ⚠ Implementation detail: the rows already exist as `''` on live installs,
   and `insertDefault` is `ON CONFLICT DO NOTHING` — these seeds need "fill if currently
   blank" semantics at boot, not insert-only.
2. **Redirect URI**: if `GOOGLE_OAUTH_REDIRECT_URI` is set (seeded to
   `sync.oauth.redirect_uri`), `SyncOAuthService` uses it verbatim (it must match what's
   registered in Google Cloud Console); otherwise derive from the request origin as today.
3. **UI hide-when-set**: the panel renders a credential field ONLY when it's not yet
   set (server already reports `syncClientId` + `…Set` booleans). When everything is
   present the panel is just: status line, Connect/Disconnect, auto-sync toggle, Sync
   now / Pull buttons. A small "edit credentials" link un-hides the fields — pure
   hiding would make a typo'd secret unfixable from the UI.
4. **Connect gating becomes self-explanatory**: when disabled, the status line says
   exactly what's missing ("no OAuth client configured — set GOOGLE_OAUTH_CLIENT_ID/…
   in .env or click edit credentials").
5. **Folder-id vs `drive.file` scope (latent breakage, must fix with this)**: the
   seeded `GOOGLE_DRIVE_FOLDER_ID` points at a folder the OAuth app did NOT create;
   under the `drive.file` scope the app cannot see or write it → first upload after
   connecting would 404. On successful OAuth connect, clear `sync.drive.folder_id` so
   the auto-created "ObsidianOptimizer" folder takes over. (Explicit custom folders
   under OAuth would need the full `drive` scope — not worth the consent screen.)

---

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

## Part B — janitor + retention (decisions locked 2026-07-02)

User constraints: big vault, small Drive quota, weekly cadence
(`SYNC_UPLOAD_CRON=0 0 3 * * SUN` — already an env var, no code), wants "come back to a
previous version" + old copies cleaned. Resolution: the mirror + Drive's native
~30-day/100-revision auto-pruned version history covers rollback; the janitor (phases
0–2 below) removes the only unbounded growth (orphans). Revisions need no cleanup code —
Drive purges them itself; do NOT set keepRevisionForever anywhere. No new Google
permission needed: `drive.file` already allows deleting app-created files.

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
