# Backend Flows

Files: ObsidianApplication.java, MyController.java, FileRepository.java, NoteLinkRepository.java, ImageRepository.java, WebConfig.java, SecurityConfig.java, ServletInitializer.java

---

## Startup

`ObsidianApplication.main()` → `SpringApplication.run()` → beans init: `FileRepository`, `ImageRepository`, `MyController`, `SecurityConfig` → Tomcat on port 8082  
To change port: `application.properties → server.port`

---

## Auth (SecurityConfig.java)

Spring Security session-based single-user auth.  
Credentials in `application.properties`: `app.auth.username`, `app.auth.password` (raw — BCrypt applied at startup)

`POST /login` (form-encoded: `username=&password=`) → Spring Security validates → sets session cookie → returns 200  
`POST /logout` → invalidates session → returns 200  
`GET /me` → 200 + username if authenticated, 401 if not

Public GET endpoints: `/names`, `/review`, `/text`, `/images/**`  
Protected (require session): all POST, PUT, PATCH, DELETE + `GET /me`

To change credentials: `application.properties`  
To add/remove protected endpoints: `SecurityConfig.filterChain()` `authorizeHttpRequests`

---

## GET /names — All Note Paths

`MyController.getNames()` → `FileRepository.getNoteNames()` → check `cacheUpToDate`  
- Cache hit: return `cache`  
- Cache miss: BFS from `ROOT_FILE`, skip `.git`, `resources`, `_trash` → collect `.md` paths → sort → cache → return  
To change vault root: `FileRepository.ROOT_FILE` (line 18)

---

## GET /review — Notes Due for Review

`MyController.getReviewNames()` → `FileRepository.getReviewNotes()` → check `cacheReviewUpToDate`  
- Cache miss: calls `getNoteNames()` → for each path, read line 2, extract date after `"reviewed: "` → compare to today → add if ≤ today  
Format expected on line 2: `reviewed: yyyy-MM-dd`  
To change review format: `FileRepository.getReviewNotes()` + `isBeforeToday()`

---

## GET /text?noteName={path} — Note Content

`MyController.getText(noteName)` → `FileRepository.getText(path)` → `Files.readString()` → returns raw markdown

---

## GET /images/{filename} — Image Serving

`ImageRepository.getImage(filename)` → `serveFile(IMAGE_DIR, filename)` → validate exists → detect MIME → return `ResponseEntity<Resource>`  
Image dir: `ImageRepository.imageDir` (hardcoded `C:\Users\ACER\Desktop\NewLife\resources\images`)

---

## POST /notes — Create Note

`MyController.createNote(CreateNoteRequest{folder, name})`  
→ `FileRepository.createNote(folderPath, name)` → validate folder exists → create `name.md` with frontmatter skeleton → `invalidateCache()` → return `{path: absolutePath}`  
Initial frontmatter: `---\nsr-due: {today+3d}\nsr-interval: 3\nsr-ease: 200\n---\n\n`  
`sr-due` prefix is 8 chars — matches `isBeforeToday()` substring(8,18) extraction  
To change defaults: `FileRepository.createNote()`

---

## PUT /notes — Update Note Content (full replace, kept for fallback)

`MyController.updateNote(UpdateNoteRequest{path, content})`  
→ `FileRepository.updateNote(path, content)` → validate file exists → `Files.writeString()` → `invalidateCache()`

---

## PATCH /notes/content — Diff-based Update

`MyController.patchNote(PatchNoteRequest{path, hunks})`  
→ `FileRepository.patchNote(path, hunks)`  
1. Read file → detect line separator (`\r\n` or `\n`) from raw bytes  
2. Normalize to LF → split into `List<String> lines` (with `-1` limit to keep trailing empty line)  
3. Sort hunks descending by `startLine` (apply back-to-front so indices stay valid)  
4. For each hunk: remove `deleteCount` lines at `startLine`, insert `insertLines` at `startLine`  
5. `String.join(sep, lines)` → `Files.writeString()` → `invalidateCache()`

Hunk DTO: `FileRepository.PatchHunk(int startLine, int deleteCount, List<String> insertLines)`  
Security: PATCH is session-protected — same as PUT  
Frontend diff: `utils/diff.js computeHunks(oldText, newText)` → LCS algorithm, CRLF-normalized  
Zero hunks (no change) → frontend skips the PATCH call entirely  
To change diff algorithm: `frontend/src/utils/diff.js lcsBacktrack()`

---

## PATCH /notes/rename — Rename Note

`MyController.renameNote(RenameNoteRequest{oldPath, newName})`  
→ `FileRepository.renameNote(oldPath, newName)`  
1. Derive `oldName` (basename without `.md`)  
2. Query `NoteLinkRepository.findSourcesByTarget(oldName)` → list of files that contain `[[oldName]]`  
3. `File.renameTo()` → new path on disk  
4. For each source file: read → `NoteLinkRepository.rewriteLinks(content, oldName, newName)` → write back (skips if content unchanged)  
5. `NoteLinkRepository.renameTarget(oldName, newName)` — bulk-update `note_links` target column  
6. `NoteLinkRepository.renameSource(oldPath, newPath)` — update the renamed note's own source entry  
7. `invalidateCache()` → return `{path: newAbsolutePath}`

Handles: `[[NoteA]]`, `[[NoteA|display]]`, `[[Folder/NoteA]]`, `[[Folder/NoteA|display]]`

---

## DELETE /notes — Soft Delete

`MyController.deleteNote(DeleteNoteRequest{path})`  
→ `FileRepository.softDeleteNote(path)` → validate exists → ensure `ROOT_FILE/_trash/` exists → move file there (timestamp suffix if name conflict) → `NoteLinkRepository.deleteSource(path)` (removes outgoing links from adjacency table) → `invalidateCache()`  
`_trash/` is skipped by `getNoteNames()` — files there are invisible to the app  
Incoming links from other notes are left in `note_links` as dead entries — they become dead `[[links]]` in those files, consistent with Obsidian's own behaviour  
Recovery: manual file move [NOT IMPLEMENTED in UI]

---

## Cache Invalidation

`FileRepository.invalidateCache()` sets `cacheUpToDate = false` AND `cacheReviewUpToDate = false`  
Called automatically after every write (create, update, rename, delete)  
No HTTP endpoint to trigger manually — restart or any write op clears both caches

**CACHE CURRENTLY DISABLED** — `getNoteNames()` and `getReviewNotes()` ignore `cacheUpToDate` flags and always recompute. Re-enable by uncommenting the guard at the top of each method in `FileRepository.java` once the app is stable and the feedback loop is trusted.

---

## NoteLinkRepository — Wiki-link Adjacency Table

`note_links(source_path TEXT, target_name TEXT, PRIMARY KEY(source_path, target_name))`  
Index on `target_name` for O(k) rename lookups where k = number of backlinks.

**Lifecycle:**
- `@PostConstruct initSchema()` — `CREATE TABLE IF NOT EXISTS` + index on startup
- `FileRepository.@PostConstruct init()` — calls `backfillIfEmpty(getNoteNames())`: reads all notes and seeds the table on first boot (skipped if table already has rows)
- `updateLinks(path, targets)` — called after every write (create / update / patch)
- `deleteSource(path)` — called on soft-delete

**Extraction:** `extractTargets(markdown)` — regex `\[\[([^|\]]+)(?:\|[^\]]+)?\]\]`, strips path prefix, returns Set of basenames  
**Rewrite:** `rewriteLinks(content, oldName, newName)` — regex replaces `[[oldName]]` / `[[.../oldName]]` / `[[oldName|text]]` in one pass

To change link storage: `NoteLinkRepository.java`  
To force re-index: truncate `note_links` table → restart app (backfill triggers)

---

## Infrastructure

Three services run in Docker via `docker-compose.yml` at the repo root.  
Start everything: `.\start.ps1` (or `docker compose up --build`)  
Stop everything: `Ctrl+C` in the same terminal → all containers stop cleanly

Config for friends: copy `.env.example` → `.env`, set `HOST_VAULT_PATH` to vault directory.  
Vault is mounted read-write at `/vault` inside the backend container.

### Technology Notes
- **WAR + embedded Tomcat**: `java -jar app.war` works because Spring Boot rewrites the WAR to be self-executable even though `spring-boot-starter-tomcat` is `provided` scope.
- **Volume mount on Windows (Docker Desktop)**: reads/writes work correctly; file watching (inotify/WatchService) does NOT propagate from host → container. This is why the backend uses a flag-based cache rather than a WatchService — WatchService would silently miss Obsidian edits when running in Docker.
- **Docker network**: frontend and backend are on the default compose network; nginx proxies `/api/*` → `backend:8084` by service name. No `host.docker.internal` needed.
- **pgvector container**: `pgvector/pgvector:pg16` — backend waits on `service_healthy` (pg_isready). Postgres data persisted in named volume `postgres_data`. Local dev needs postgres on `localhost:5432` or run via `docker compose up`.
- **note_links backfill**: runs once on first boot when the table is empty. To re-index from scratch: `TRUNCATE note_links;` in psql → restart backend.
- **note_links partial invalidation**: `updateLinks(path, targets)` is per-note (DELETE WHERE source_path + INSERT) — only the written note's rows are replaced. Rename uses `renameTarget` / `renameSource` SQL UPDATEs — no full-table rebuild. Full rebuild only happens on first-boot backfill or manual TRUNCATE.
- **note_links external-edit staleness**: edits made directly in Obsidian (outside the app) are NOT reflected in `note_links`. The table only updates when writes go through the app's API. Consequence: a backlink added externally will not be found by `findSourcesByTarget()` during rename. Workaround: TRUNCATE + restart to force a full re-index. WatchService cannot fix this — inotify does not propagate through Docker volume mounts on Windows.
- **file-name cache (in-memory ArrayList)**: currently fully invalidated on every write. Partial invalidation (splice the one renamed path) is possible but not implemented — cache is disabled anyway. To re-enable cache: uncomment guards in `FileRepository.getNoteNames()` and `getReviewNotes()`. To make rename update cache without full rebuild: update `cache` ArrayList in-place inside `renameNote()`.

---

## Change Index

| Thing to change | Where |
|---|---|
| Vault root path | `.env` → `HOST_VAULT_PATH` (host) / `VAULT_PATH` env var (container) |
| Image directory | `IMAGE_PATH` env var in `docker-compose.yml` (defaults to `VAULT_PATH/resources/images`) |
| Server port | `application.properties → server.port` + `docker-compose.yml` port mapping |
| Auth credentials | `application.properties → app.auth.username / app.auth.password` |
| Review date format | `FileRepository.isBeforeToday()` |
| Directories skipped in BFS | `FileRepository.getNoteNames()` skip list |
| Initial note frontmatter | `FileRepository.createNote()` |
| Diff algorithm | `frontend/src/utils/diff.js lcsBacktrack()` |
| Patch hunk DTO | `FileRepository.PatchHunk` |
| Protected vs public endpoints | `SecurityConfig.filterChain()` |
| Wiki-link regex (extract) | `NoteLinkRepository.WIKI_LINK` pattern |
| Wiki-link regex (rewrite) | `NoteLinkRepository.rewriteLinks()` |
| Postgres connection | `application.properties` (overridden by `SPRING_DATASOURCE_*` env vars) |
| Postgres credentials | `docker-compose.yml` + `.env` `POSTGRES_PASSWORD` |
| Re-enable file-name cache | Uncomment guards in `FileRepository.getNoteNames()` + `getReviewNotes()` |
| Make rename partially update file-name cache | Update `cache` ArrayList in-place in `FileRepository.renameNote()` (not yet done) |
| Force note_links full re-index | `TRUNCATE note_links;` in psql → restart backend |
| Partial note_links invalidation trigger | Implicit — every app-side write calls `updateLinks(path, targets)` automatically |

---

## Residual (next session)

- **HTTP cache invalidation endpoint** — optional `/admin/invalidate` for cache clearing without restart
