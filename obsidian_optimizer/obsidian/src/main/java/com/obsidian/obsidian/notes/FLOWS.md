# Notes Domain Flows

Files: NotesController.java, FileRepository.java, NoteIndexRepository.java, NoteLinkRepository.java, FrontmatterParser.java

---

## Startup Sync

`FileRepository.@PostConstruct init()` → reads `vaultPath` from `SettingsRepository` → `bfsDiskFiles()` → `NoteIndexRepository.syncWithDisk(diskFiles)`

`startupSyncMode = "blocking"` (default): sync runs before app accepts requests  
`startupSyncMode = "async"`: `CompletableFuture.runAsync()`, app starts immediately

To change sync mode: `PUT /api/settings { startupSyncMode }` → `SettingsRepository`  
To change excluded dirs: `FileRepository.EXCLUDED_DIRS`

### Delta Algorithm

`NoteIndexRepository.syncWithDisk(diskFiles)`:

```
diskMap = { path → File }           from bfsDiskFiles()
dbMap   = { path → modified_at }    from SELECT path, modified_at FROM notes

for each diskFile:
  if path not in dbMap:             → INSERT (new)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  elif file.lastModified() > dbMap[path]:  → UPDATE (changed externally)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  else:                             → skip (no I/O)

for each dbPath not in diskMap:     → DELETE (removed externally)
  delete() + deleteSource()
```

`forceResync(diskFiles)` — TRUNCATE notes + TRUNCATE note_links → full syncWithDisk  
To trigger full re-index at runtime: `PUT /api/settings { vaultPath }` (same path re-accepted)

---

## FrontmatterParser

`FrontmatterParser.parse(rawContent)` → `NoteMetadata(srDue, srInterval, srEase)`  
Normalises `\r\n → \n`, finds `--- … ---` block, splits lines on `:`.  
Keys: `sr-due` (DATE), `sr-interval` (INT), `sr-ease` (INT).  
Returns `null` fields for any missing or unparseable key.

To add a new frontmatter field: `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()`

---

## NoteIndexRepository — notes table

```sql
notes(
  path        TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  sr_due      DATE,           -- NULL = no review date
  sr_interval INT,
  sr_ease     INT,
  modified_at BIGINT NOT NULL -- file.lastModified() epoch ms
)
INDEX idx_notes_sr_due ON notes(sr_due)
```

Key methods:
- `syncWithDisk(diskFiles)` — delta sync (see above)
- `forceResync(diskFiles)` — TRUNCATE notes + note_links → full syncWithDisk
- `getAllPaths()` — `SELECT path FROM notes ORDER BY path`
- `getReviewNotesPaged(offset, limit)` — `WHERE sr_due <= CURRENT_DATE ORDER BY sr_due, path LIMIT limit+1 OFFSET offset` (limit+1 avoids COUNT)
- `upsert(path, title, meta, modifiedAt)` — INSERT … ON CONFLICT DO UPDATE
- `rename(oldPath, newPath, newTitle)` — UPDATE path + title
- `delete(path)` — DELETE

---

## NoteLinkRepository — note_links table

```sql
note_links(source_path TEXT, target_name TEXT, PRIMARY KEY(source_path, target_name))
INDEX ON target_name   -- O(k) rename lookups
```

`updateLinks(path, targets)` — DELETE existing rows for source + INSERT new targets  
`findSourcesByTarget(name)` — backlink lookup for rename  
`renameTarget(old, new)` / `renameSource(oldPath, newPath)` — sync after rename  
`truncateLinks()` — used before `forceResync`

**Staleness note:** external Obsidian edits caught at next startup sync only. Docker volume mounts on Windows don't propagate inotify events.

---

## GET /names

`NotesController.getNames()` → `FileRepository.getNoteNames()` → `NoteIndexRepository.getAllPaths()`  
→ `SELECT path FROM notes ORDER BY path` — no disk I/O

---

## GET /children

`NotesController.getChildren(folder?)` → `FileRepository.getChildren(folder)` → disk `listFiles()`  
Returns `{ parentPath, filePaths[], folderPaths[] }`. `folder` absent → vault root.

---

## GET /review

`NotesController.getReviewNames(offset, limit)` → `FileRepository.getReviewNotesPaged()` → `NoteIndexRepository.getReviewNotesPaged()`  
`hasMore` via limit+1 trick — no COUNT query.  
To change sort: `NoteIndexRepository.getReviewNotesPaged()` ORDER BY clause.

---

## GET /text

`NotesController.getText(noteName)` → `FileRepository.getText(path)` → `Files.readString()` → raw markdown

---

## POST /notes — Create

`NotesController.createNote(CreateNoteRequest{folder, name})`  
→ `FileRepository.createNote(folder, name)` → validate folder → create `name.md` with frontmatter skeleton  
→ `FrontmatterParser.parse()` → `NoteIndexRepository.upsert()` → `NoteLinkRepository.updateLinks()`

Initial frontmatter: `---\nsr-due: {today+3d}\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n`  
To change defaults: `FileRepository.createNote()`

---

## PUT /notes — Full Replace

`NotesController.updateNote(UpdateNoteRequest{path, content})`  
→ `FileRepository.updateNote()` → `Files.writeString()` → parse → upsert → updateLinks

---

## PATCH /notes/content — Diff-based Update

`NotesController.patchNote(PatchNoteRequest{path, hunks})`  
→ `FileRepository.patchNote()` → apply hunks back-to-front → `Files.writeString()` → parse → upsert → updateLinks

Hunk DTO: `FileRepository.PatchHunk(int startLine, int deleteCount, List<String> insertLines)`

---

## PATCH /notes/rename

`NotesController.renameNote(RenameNoteRequest{oldPath, newName})`  
→ `FileRepository.renameNote()`:
1. `NoteLinkRepository.findSourcesByTarget(oldName)` → backlink list
2. `File.renameTo()` on disk
3. Rewrite `[[oldName]]` → `[[newName]]` in each backlink source
4. `NoteIndexRepository.rename(oldPath, newPath, newTitle)`
5. `NoteLinkRepository.renameTarget()` + `renameSource()`

---

## PATCH /notes/move

`NotesController.moveNote(MoveNoteRequest{path, targetFolder})`  
→ `FileRepository.moveNote()` → validate no collision → `Files.move(ATOMIC_MOVE)` → `NoteIndexRepository.rename()` + `NoteLinkRepository.renameSource()`

---

## DELETE /notes — Soft Delete

`NotesController.deleteNote(DeleteNoteRequest{path})`  
→ `FileRepository.softDeleteNote()` → move to `ROOT_FILE/_trash/` → `NoteIndexRepository.delete()` → `NoteLinkRepository.deleteSource()`

---

## Technology Notes

- **Startup sync blocking mode**: app does not accept requests until `syncWithDisk()` completes. Fast on unchanged vaults (DB query + Set comparison only).
- **Startup sync async mode**: `getNoteNames()` / `getReviewNotesPaged()` return partial results during sync.
- **inotify on Docker/Windows**: WatchService does not propagate from host → container via volume mount. All external edits are caught only at next startup sync.

---

## Change Index

| Thing to change | Where |
|---|---|
| Excluded dirs in BFS | `FileRepository.EXCLUDED_DIRS` |
| Initial note frontmatter | `FileRepository.createNote()` |
| Review sort order | `NoteIndexRepository.getReviewNotesPaged()` ORDER BY |
| Frontmatter keys indexed | `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()` |
| Diff hunk DTO | `FileRepository.PatchHunk` |
| Wiki-link extract regex | `NoteLinkRepository.WIKI_LINK` |
| Wiki-link rewrite regex | `NoteLinkRepository.rewriteLinks()` |
| Force full re-index | `TRUNCATE notes; TRUNCATE note_links;` in psql → restart, or `PUT /api/settings` with same vaultPath |
