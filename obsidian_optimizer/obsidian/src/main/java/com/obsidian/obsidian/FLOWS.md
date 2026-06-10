# Backend Flows

Files: ObsidianApplication.java, MyController.java, FileRepository.java, NoteLinkRepository.java, NoteIndexRepository.java, FrontmatterParser.java, ImageRepository.java, SettingsRepository.java, WebConfig.java, SecurityConfig.java, ServletInitializer.java

---

## Startup

Bean init order (Spring respects dependency graph):
1. `SettingsRepository` — creates `app_settings`, seeds defaults from env vars
2. `NoteLinkRepository` — creates `note_links` + index
3. `NoteIndexRepository` — creates `notes` + index
4. `FileRepository.@PostConstruct init()` — reads vault path from `SettingsRepository` → `bfsDiskFiles()` → `NoteIndexRepository.syncWithDisk()`
   - `startupSyncMode = "blocking"` (default): sync runs before app accepts requests
   - `startupSyncMode = "async"`: sync runs in `CompletableFuture.runAsync()`, app starts immediately

To change port: `application.properties → server.port`

---

## Auth (SecurityConfig.java)

Spring Security session-based single-user auth.  
Credentials in `application.properties`: `app.auth.username`, `app.auth.password` (raw — BCrypt applied at startup)

`POST /login` (form-encoded: `username=&password=`) → Spring Security validates → sets session cookie → returns 200  
`POST /logout` → invalidates session → returns 200  
`GET /me` → 200 + username if authenticated, 401 if not

`GET /settings` public. All other endpoints require session auth.  
To add/remove protected endpoints: `SecurityConfig.filterChain()` `authorizeHttpRequests`

---

## Startup Sync — Delta Algorithm

`NoteIndexRepository.syncWithDisk(diskFiles)`:

```
diskMap = { path → File }           from bfsDiskFiles()
dbMap   = { path → modified_at }    from SELECT path, modified_at FROM notes

for each diskFile:
  if path not in dbMap:             → INSERT (new note)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  elif file.lastModified() > dbMap[path]:  → UPDATE (changed externally)
    read content → FrontmatterParser.parse() → upsert() + updateLinks()
  else:                             → skip (unchanged, no I/O)

for each dbPath not in diskMap:     → DELETE (removed externally)
  delete() + deleteSource()
```

Only reads file content for new or modified notes — unchanged notes cost zero disk I/O.

---

## FrontmatterParser

`FrontmatterParser.parse(rawContent)` → `NoteMetadata(srDue, srInterval, srEase)`  
Normalises `\r\n` → `\n`, finds `---` … `---` block, splits lines on `:`.  
Returns `null` fields for any key not present or unparseable.  
Keys: `sr-due` (DATE), `sr-interval` (INT), `sr-ease` (INT).

Canonical frontmatter format (written by `createNote`):
```
---
sr-due: yyyy-MM-dd
sr-interval: 3
sr-ease: 200
---
```
To add a new frontmatter field to the index: `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()`.

---

## NoteIndexRepository — notes table

Schema:
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
- `forceResync(diskFiles)` — TRUNCATE notes + TRUNCATE note_links → full syncWithDisk
- `getAllPaths()` — `SELECT path FROM notes ORDER BY path`
- `getReviewNotesPaged(offset, limit)` — `WHERE sr_due <= CURRENT_DATE ORDER BY sr_due, path LIMIT limit+1 OFFSET offset` (limit+1 trick avoids COUNT query)
- `upsert(path, title, meta, modifiedAt)` — INSERT … ON CONFLICT DO UPDATE
- `rename(oldPath, newPath, newTitle)` — UPDATE path + title
- `delete(path)` — DELETE

---

## GET /names — All Note Paths

`MyController.getNames()` → `FileRepository.getNoteNames()` → `NoteIndexRepository.getAllPaths()`  
→ `SELECT path FROM notes ORDER BY path`  
No disk I/O. O(N) result scan.

---

## GET /review — Notes Due for Review

`MyController.getReviewNames()` → `FileRepository.getReviewNotesPaged(offset, limit)` → `NoteIndexRepository.getReviewNotesPaged()`  
→ `SELECT path FROM notes WHERE sr_due <= CURRENT_DATE ORDER BY sr_due ASC, path ASC LIMIT ? OFFSET ?`  
`hasMore` detected via limit+1 trick — no separate COUNT query.  
To change review sort: `NoteIndexRepository.getReviewNotesPaged()` ORDER BY clause.

---

## GET /text?noteName={path} — Note Content

`MyController.getText(noteName)` → `FileRepository.getText(path)` → `Files.readString()` → returns raw markdown

---

## GET /images/{filename} — Image Serving

`ImageRepository.getImage(filename)` → `serveFile(settingsRepo.getResourcePath(), filename)` → validate exists → detect MIME → return `ResponseEntity<Resource>`  
Image dir: read from `SettingsRepository` → `app_settings.resourcePath` (default: `VAULT_PATH/resources/images`)

---

## POST /notes — Create Note

`MyController.createNote(CreateNoteRequest{folder, name})`  
→ `FileRepository.createNote(folderPath, name)` → validate folder → create `name.md` with frontmatter skeleton  
→ `FrontmatterParser.parse(initialContent)` → `NoteIndexRepository.upsert()`  
→ `NoteLinkRepository.updateLinks()` → return `{path: absolutePath}`

Initial frontmatter: `---\nsr-due: {today+3d}\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n`  
To change defaults: `FileRepository.createNote()`

---

## PUT /notes — Update Note Content (full replace, fallback)

`MyController.updateNote(UpdateNoteRequest{path, content})`  
→ `FileRepository.updateNote(path, content)` → `Files.writeString()` → `FrontmatterParser.parse()` → `NoteIndexRepository.upsert()` → `NoteLinkRepository.updateLinks()`

---

## PATCH /notes/content — Diff-based Update

`MyController.patchNote(PatchNoteRequest{path, hunks})`  
→ `FileRepository.patchNote(path, hunks)` → apply hunks back-to-front → `Files.writeString()`  
→ `FrontmatterParser.parse(newContent)` → `NoteIndexRepository.upsert()` → `NoteLinkRepository.updateLinks()`

Hunk DTO: `FileRepository.PatchHunk(int startLine, int deleteCount, List<String> insertLines)`

---

## PATCH /notes/rename — Rename Note

`MyController.renameNote(RenameNoteRequest{oldPath, newName})`  
→ `FileRepository.renameNote(oldPath, newName)`
1. `NoteLinkRepository.findSourcesByTarget(oldName)` → backlink list
2. `File.renameTo()` on disk
3. Rewrite `[[oldName]]` → `[[newName]]` in each source file
4. `NoteIndexRepository.rename(oldPath, newPath, newTitle)` — UPDATE path + title
5. `NoteLinkRepository.renameTarget(oldName, newName)` + `renameSource(oldPath, newPath)`

Cross-file rename works for notes written through the app. External edits are caught at next startup sync (delta detects `lastModified` change → `updateLinks` refreshes the row).

---

## DELETE /notes — Soft Delete

`MyController.deleteNote(DeleteNoteRequest{path})`  
→ `FileRepository.softDeleteNote(path)` → move to `ROOT_FILE/_trash/` → `NoteIndexRepository.delete(path)` → `NoteLinkRepository.deleteSource(path)`

---

## GET /settings + PUT /settings

`GET /settings` — public, returns `{vaultPath, resourcePath, reviewPageSize, startupSyncMode}`  
`PUT /settings` — auth required, partial update (all fields optional)

`vaultPath` change → `FileRepository.updateVaultPath()` → validates dir → saves to DB → sets `ROOT_FILE` → `NoteIndexRepository.forceResync()` (TRUNCATE notes + note_links → full delta sync)  
`startupSyncMode` — `"blocking"` or `"async"`, validated in controller  
`reviewPageSize` — 1–500, validated in controller

---

## SettingsRepository — app_settings table

`app_settings(key TEXT PRIMARY KEY, value TEXT)`  
Seeded on first boot from env vars with `ON CONFLICT DO NOTHING`.

| Key | Default | Source |
|---|---|---|
| `vaultPath` | `$VAULT_PATH` | env var |
| `resourcePath` | `$IMAGE_PATH` or `$VAULT_PATH/resources/images` | env var |
| `reviewPageSize` | `20` | hardcoded |
| `startupSyncMode` | `"blocking"` | hardcoded |

To force re-seed: `DELETE FROM app_settings;` → restart.

---

## NoteLinkRepository — note_links adjacency table

`note_links(source_path TEXT, target_name TEXT, PRIMARY KEY(source_path, target_name))`  
Index on `target_name` for O(k) rename lookups.

**Lifecycle:**
- `initSchema()` — `CREATE TABLE IF NOT EXISTS` + index
- `syncWithDisk()` (via `NoteIndexRepository`) — calls `updateLinks()` for every new/changed note
- `truncateLinks()` — truncate only (used before `forceResync`)
- `forceRebuildLinks(paths)` — truncate + full backfill from a path list

**Staleness note:** external Obsidian edits are caught at next startup sync. No mid-session inotify — Docker volume mounts on Windows don't propagate inotify events.

---

## Infrastructure

Three services via `docker-compose.yml`. Start: `.\start.ps1`. Stop: `Ctrl+C`.

### Technology Notes
- **WAR + embedded Tomcat**: `java -jar app.war` is self-executable despite `provided` scope.
- **Volume mount on Windows**: file watching (inotify/WatchService) does NOT propagate from host → container. Startup delta sync handles external edits instead.
- **pgvector container**: `pgvector/pgvector:pg16` — backend waits on `service_healthy`. Data persisted in `postgres_data` volume.
- **Startup sync blocking mode**: app does not accept requests until `NoteIndexRepository.syncWithDisk()` completes. On large vaults with no changes, this is fast (only a DB query + Set comparison). On first boot or after many external edits, it reads changed files from disk.
- **Startup sync async mode**: app starts immediately. `getNoteNames()` and `getReviewNotesPaged()` return partial results during sync. Choose this for faster boot at the cost of a brief stale window.

---

## Change Index

| Thing to change | Where |
|---|---|
| Vault root path | Settings page → `PUT /api/settings` → `FileRepository.updateVaultPath()` |
| Image directory | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Review page size | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Startup sync mode | Settings page → `PUT /api/settings` → `SettingsRepository` |
| Server port | `application.properties → server.port` |
| Auth credentials | `application.properties → app.auth.username / app.auth.password` |
| Frontmatter keys indexed | `FrontmatterParser.parse()` + `NoteIndexRepository` schema + `upsert()` |
| Review sort order | `NoteIndexRepository.getReviewNotesPaged()` ORDER BY |
| Directories skipped in BFS | `FileRepository.EXCLUDED_DIRS` |
| Initial note frontmatter | `FileRepository.createNote()` |
| Diff algorithm | `frontend/src/utils/diff.js lcsBacktrack()` |
| Patch hunk DTO | `FileRepository.PatchHunk` |
| Protected vs public endpoints | `SecurityConfig.filterChain()` |
| Wiki-link regex (extract) | `NoteLinkRepository.WIKI_LINK` pattern |
| Wiki-link regex (rewrite) | `NoteLinkRepository.rewriteLinks()` |
| Postgres connection | `application.properties` (overridden by `SPRING_DATASOURCE_*` env vars) |
| Force notes full re-index | `TRUNCATE notes; TRUNCATE note_links;` in psql → restart backend |

---

## Residual (next session)

- **HTTP resync endpoint** — optional `POST /admin/resync` to trigger delta sync without restart
- **Trash UI** — list and restore notes from `_trash/`
