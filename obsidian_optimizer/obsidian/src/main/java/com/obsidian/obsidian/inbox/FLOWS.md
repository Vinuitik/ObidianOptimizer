# Inbox (triage) — FLOWS
Files: InboxController.java, ../notes/NoteIndexRepository.java (review exclusion), ../workspace/WorkspaceController.java (sibling pattern), embedder ingest/publish.py (producer)

> The Learn **Inbox** is the triage queue for notes the ingest agent generated. Ingest
> parks standalone notes in the vault's `_inbox/` staging folder instead of auto-filing
> them; the user reviews, edits, and files each one into a real folder — which is what
> moves it into the FSRS review queue.

## Producer → staging → triage
```
extension /capture → embedder ingest → publish.create_note(_inbox, …)   [stamps frontmatter]
   ingest-inbox: true / ingest-source: <url> / ingest-suggested-folder: <find_home guess>
                                   │
Learn Inbox tab ── GET /api/inbox ─┘  (InboxController scans _inbox/, parses frontmatter)
   user edits + picks folder → POST /api/inbox/file {path, targetFolder, content}
     → updateNote(strip inbox frontmatter) → moveNote(path → folder)  → enters review
   or → DELETE /api/inbox {path} → softDeleteNote (to _trash)
```

## Endpoints (nginx strips `/api/` → controller maps `/inbox`)
- `GET /inbox` — `InboxController.list()` scans `vault/_inbox/*.md`, returns
  `{path(absolute), title, source, suggestedFolder(absolute), content}` per note.
  `suggestedFolder` is converted vault-relative → absolute so it matches the
  `/children` folder list the UI picks from (`suggestedFolderAbs()`).
- `POST /inbox/file {path, targetFolder, content}` — save edits + move out of `_inbox`.
  Strips the `ingest-*` frontmatter (`stripInboxFrontmatter()`), `updateNote`, then
  `moveNote`. `targetFolder` is absolute; `Files.createDirectories` makes it if missing.
- `DELETE /inbox {path}` — discard a generated note (soft-delete).

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
  filters it out of the destination datalist client-side.
- **Frontmatter parse is a cheap scalar lookup** (`frontmatterValue()`), not a YAML
  parser — fine for the three flat `ingest-*` keys ingest writes. A multi-line/quoted
  value would not parse; ingest never writes those here.
- **No pagination.** `GET /inbox` lists the whole `_inbox/` dir. Fine for a personal
  backlog; if it grows huge, add paging like `getReviewNotesPaged`.

## Change Index
| Touch this | Where |
|---|---|
| Staging folder name | `InboxController.INBOX_DIR` + embedder `publish.INBOX_FOLDER` + the review-query `LIKE` |
| Review-queue exclusion | `NoteIndexRepository.getReviewNotesPaged` (`path NOT LIKE '%/_inbox/%'`) |
| Frontmatter keys stripped on file | `InboxController.stripInboxFrontmatter()` |
| Suggested-folder resolution | `InboxController.suggestedFolderAbs()` |
| Triage UI | `frontend/.../organisms/InboxPanel.jsx` + `api/inbox.js` |
