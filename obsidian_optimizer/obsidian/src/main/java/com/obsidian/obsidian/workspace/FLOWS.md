# Workspace (media shelf) — FLOWS
Files: WorkspaceController.java, WorkspaceRepository.java

> `_workspace/` is the **manual drag-and-drop media shelf** the Learn page browses
> (PDF / video / audio tabs, `ResourcePanel.jsx`). It is NOT the ingest review queue —
> that is `_inbox/` (see `../inbox/FLOWS.md`). Files land here by explicit user action
> (extension drop, save-from-URL); nothing here is AI-processed or FSRS-tracked.

## Why a table (`workspace_items`) instead of a dir scan
A listing needs each file's **type** (video|audio|pdf) and a stable **sort order**.
Deriving those from a raw `Files.list()` means opening/inspecting every file per request.
`workspace_items` holds `filename, type, source_note, importance, added_at` so listing is
one indexed query. Every write path into `_workspace/` upserts a row → the DB is the
source of truth; the filesystem holds only bytes.

```
POST /workspace/save {url}   → download → _workspace/<name> → workspaceRepo.insert(...)
POST /workspace/upload (file)→ store   → _workspace/<name> → workspaceRepo.insert(...)
GET  /workspace/files        → workspaceRepo.listAll() (ORDER BY importance DESC, added_at DESC)
                                prune rows whose file vanished (self-heal, no cron)
GET  /workspace/<name>       → MediaController serves the bytes (video/audio/pdf viewer)
```

`listFiles()` prunes on read: a row whose file is gone (user deleted it by hand) is
deleted right there — `_workspace/` is user-worked only, so no janitor is needed.

## Technology Notes (constraints / failure modes)
- **`importance` is currently always 0.** The column exists for a future ranked shelf;
  both writers pass 0. Sort therefore falls through to `added_at DESC` (newest first).
- **`_workspace` is an EXCLUDED_DIR** (`FileRepository.EXCLUDED_DIRS`) — its files never
  enter the note index, review queue, or embedding pipeline. Pure playback shelf.
- **512 MB cap** (`WorkspaceController.MAX_BYTES`) on both save and upload; oversized or
  disallowed-extension inputs are rejected before any DB row is written.
- **No transaction across FS + DB.** File is written first, then the row upserted. A
  crash between the two leaves an untracked file (invisible to listing) — harmless, and
  a re-upload upserts idempotently. The reverse (row, no file) is pruned on next read.
- **Not the ingest queue.** Do not add ingest/review state here. Confusing the two folders
  was a real design misstep — `_inbox/` owns review, `_workspace/` owns playback.

## Change Index
| Touch this | Where |
|---|---|
| Accepted extensions / type mapping | `WorkspaceController.VIDEO_EXTS/AUDIO_EXTS/PDF_EXTS` + `typeFor()` |
| Size cap | `WorkspaceController.MAX_BYTES` |
| Listing sort order | `WorkspaceRepository.listAll()` ORDER BY |
| Table schema | `WorkspaceRepository.initSchema()` (`workspace_items`) |
| Stale-row pruning | `WorkspaceController.listFiles()` (delete-on-missing) |
| Shelf UI | `frontend/.../organisms/ResourcePanel.jsx` + `api/workspace.js` |
