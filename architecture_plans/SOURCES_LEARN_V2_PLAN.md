# Sources → Learn → Notes → Review — plan v2

Status: APPROVED 2026-10-20 (supersedes the IA parts of LEARN_SOURCES_PLAN.md).

## Decisions (user, 2026-10-20)
- Per-vault DB backup: each vault's Drive gets only its own rows (no whole-DB leak).
- Week view shows progress badges (done/planned per day).
- Tracks lose ALL data intake. Single intake path: Sources → capture → ingest → Inbox.
- Learn = pre-processing step between raw data and notes; generation runs automatically.
- No "inactive vault" GC. Data is deleted only when the user asks — and then via a durable queue.
- Default landing page = Review.

## Milestones
- D1 per-vault backup (sync/): DbBackupService exports vault-scoped tables with
  `COPY (SELECT … WHERE vault_id=?)`; global tables (vaults registry, global settings) only in the
  primary vault's dump; restore per vault (delete that vault's rows + COPY in, one tx). Full
  pg_dump stays local only (linux_scripts/pg-backup.sh).
- M7b user-requested vault deletion (vaults/): `POST /api/vaults/{id}/delete` enqueues a durable
  job (not active, not primary, not last) → worker writes a per-vault dump first, then purges every
  vault-scoped row. Never deletes vault files on disk. Settings → Vaults gets a confirm-guarded button.
- I1 tracks intake removal (backend + embedder): tracks = group EXISTING notes into a course +
  schedule; minicourse KEPT (generates lessons from a track's notes). Remove only raw-data intake
  into tracks (URL/resource add, feed accept → track, capture.track_id, MCP track-resource intake).
- B1 backup wiring: DbBackupService implements vaults.VaultBackup; pre-delete backup is Drive-only —
  delete refused (409) when vault has no Drive/encryption; backup must throw, never silently skip.
  Drive refresh token (and other device-local secrets) never overwritten on restore.
- W1 Week badges: `/tracks/week` returns done+planned per day.
- L2/L3b frontend: Sources page (only intake screen), Learn = Inbox-first pre-processing + Week,
  default route `/` → Review, remove track-intake UI.

Each milestone: tests via linux_scripts/test.sh, FLOWS.md updated, merged to master. No deploy
until the user says "deploy".
