# Inbox (triage) — FLOWS
Files: InboxController.java, ../capture/CaptureRepository.java (in-place source), ../notes/NoteIndexRepository.java (review exclusion + findNotesByCapture), ../internalapi/InternalAgentController.java (/capture producer), embedder ingest/publish.py + jobs.py (producers)

> The Learn **Inbox** is the triage queue for **everything the ingest agent touched**.
> Two shapes share one queue (`InboxItem.inPlace` distinguishes them):
> - **standalone** (`inPlace=false`) — a *new* note parked in `_inbox/`; the user edits
>   it, picks a folder, and **files** it → moves it into the FSRS review queue.
> - **in-place** (`inPlace=true`) — an *existing* note rewritten below a resource embed.
>   It never left its real folder or FSRS rotation; the user edits it and **acknowledges**.
>   Found via `capture_id`, not a directory scan — see INGESTION_V2_FLOWS §7 lifecycle.

## Producers → staging → triage
```
STANDALONE:
  extension /capture → embedder ingest → publish.create_note(_inbox, …)  [stamps frontmatter]
     ingest-inbox: true / ingest-source: <url> / ingest-suggested-folder: <find_home guess>

IN-PLACE:
  note contains ![[lecture.mp4]] → ResourceScanService → embedder /ingest {note_path}
     jobs._synthesize_and_inject:
       snapshot pre-rewrite note content
       → publish.create_capture(capture_id, note_path, content)
             → POST /api/internal/capture → writes _inbox/_sources/{captureId}.md
               + capture row (source_type='note', source_path=snapshot, status='processing')
       → inject block in place + publish.stamp_capture(capture_id, seq=1)  [note stays live]
                                   │
Learn Inbox tab ── GET /api/inbox ─┤  standalone: scan _inbox/*.md, parse frontmatter
                                   └  in-place:   capture rows (source_type='note',
                                                  status processing|ready) → findNotesByCapture
   standalone → POST /api/inbox/file {path, targetFolder, content}
       → updateNote(strip inbox frontmatter) → moveNote → fileCapture() → enters review
   in-place   → updateNote(path, edits) [session API] → POST /api/inbox/acknowledge {captureId}
       → fileCapture()  (note already home; nothing moves)
   or standalone → DELETE /api/inbox {path} → softDeleteNote (to _trash)

fileCapture(captureId): capture.status='filed' → softDeleteFile(source_path → _trash)
   The single source (the note SNAPSHOT, not the embedded media — a note may hold
   several embeds, a Capture always has ONE source) is soft-deleted on completion.
```

## Endpoints (nginx strips `/api/` → controller maps `/inbox`)
- `GET /inbox` — `InboxController.list()` merges two sources → `{path(absolute), title,
  source, suggestedFolder(absolute), content, captureId, captureSeq, inPlace}` per item.
  Standalone: scans `vault/_inbox/*.md` (`_sources/` excluded, it's an EXCLUDED_DIR).
  In-place: `captureRepo.listAll()` filtered to `source_type='note'` +
  status `processing|ready`, expanded via `noteIndex.findNotesByCapture`.
- `POST /inbox/file {path, targetFolder, content}` — **standalone only.** Save edits +
  move out of `_inbox`; strips `ingest-*` frontmatter, `updateNote`, `moveNote`, then
  `fileCapture` when the capture's last note leaves `_inbox`.
- `POST /inbox/acknowledge {captureId}` — **in-place only.** No move (note is already
  home) — just `fileCapture(captureId)`. Save edits first via the session notes PUT.
- `DELETE /inbox {path}` — discard a standalone generated note (soft-delete).

## Why `_inbox` is indexed but not reviewed
`createNote`/`updateNote` always upsert to the note index regardless of folder, so a
truly "excluded" `_inbox` would be inconsistent (created notes get indexed anyway).
Instead `_inbox/` stays indexed (so `moveNote`'s index rename works) but is filtered
out of the review query: `NoteIndexRepository.getReviewNotesPaged` adds
`AND path NOT LIKE '%/_inbox/%'`. To change the staging folder name you must update BOTH
that SQL and embedder `publish.INBOX_FOLDER`.

## Technology Notes (constraints / failure modes)
- **Path dialect.** The public notes API speaks ABSOLUTE paths (`moveNote`,
  `updateNote`, the review bundle); the embedder INTERNAL API takes vault-relative
  folders. The Inbox endpoints return/accept absolute paths to match the frontend.
- **`_inbox` shows in the folder picker** unless filtered — it is NOT in
  `FileRepository.EXCLUDED_DIRS` (it must stay indexable). The Learn `InboxPanel`
  filters it out of the destination datalist client-side. The **nested** `_inbox/_sources/`
  IS in `EXCLUDED_DIRS` — snapshots are pre-rewrite copies, never real notes.
- **In-place notes stay in FSRS the whole time.** Unlike standalone `_inbox/` notes,
  an in-place note is never excluded from review — it pre-existed and is legitimately
  live. Acknowledge is a *review-queue clear + snapshot cleanup*, not a state gate on
  the note itself. Editing before acknowledge uses the ordinary session notes PUT.
- **In-place listing is a full capture scan.** `GET /inbox` calls `captureRepo.listAll()`
  each time and `getText` per in-place note. Fine for a personal backlog; if captures
  grow huge, add a `WHERE source_type='note' AND status IN (...)` query to CaptureRepository.
- **Soft-delete, never hard.** `fileCapture` moves the source snapshot to `_trash/` via
  `softDeleteFile` — recoverable. INGESTION_V2 wants delete-by-default eventually, but
  the snapshot here is cheap text, so trash is the safe default.
- **Frontmatter parse is a cheap scalar lookup** (`frontmatterValue()`), not a YAML
  parser — fine for the flat `ingest-*`/`capture-*` keys ingest writes.
- **No pagination.** `GET /inbox` lists the whole `_inbox/` dir + all open captures.

## Change Index
| Touch this | Where |
|---|---|
| Staging folder name | `InboxController.INBOX_DIR` + embedder `publish.INBOX_FOLDER` + the review-query `LIKE` |
| Source-snapshot folder | `InternalAgentController.createCapture` (`_inbox/_sources`) + `EXCLUDED_DIRS` |
| In-place capture listing filter | `InboxController.list()` (`source_type='note'`, status gate) |
| Acknowledge (in-place) | `InboxController.acknowledge()` → `fileCapture()` |
| Source soft-delete on completion | `InboxController.fileCapture()` → `FileRepository.softDeleteFile()` |
| Review-queue exclusion | `NoteIndexRepository.getReviewNotesPaged` (`path NOT LIKE '%/_inbox/%'`) |
| Frontmatter keys stripped on file | `InboxController.stripInboxFrontmatter()` |
| Suggested-folder resolution | `InboxController.suggestedFolderAbs()` |
| Triage UI | `frontend/.../organisms/InboxPanel.jsx` + `api/inbox.js` |
