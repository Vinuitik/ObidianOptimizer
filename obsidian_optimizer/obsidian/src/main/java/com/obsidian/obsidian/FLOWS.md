# Backend Flows

Files: ObsidianApplication.java, MyController.java, FileRepository.java, ImageRepository.java, WebConfig.java, SecurityConfig.java, ServletInitializer.java

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
Initial frontmatter: `---\nreviewed: {today}\n---\n\n`

---

## PUT /notes — Update Note Content

`MyController.updateNote(UpdateNoteRequest{path, content})`  
→ `FileRepository.updateNote(path, content)` → validate file exists → `Files.writeString()` → `invalidateCache()`

---

## PATCH /notes/rename — Rename Note

`MyController.renameNote(RenameNoteRequest{oldPath, newName})`  
→ `FileRepository.renameNote(oldPath, newName)` → validate old exists, new doesn't → `File.renameTo()` → `invalidateCache()` → return `{path: newAbsolutePath}`

**NOTE:** Does not update `[[link]]` references in other notes. This is a known limitation — see Residual below.

---

## DELETE /notes — Soft Delete

`MyController.deleteNote(DeleteNoteRequest{path})`  
→ `FileRepository.softDeleteNote(path)` → validate exists → ensure `ROOT_FILE/_trash/` exists → move file there (timestamp suffix if name conflict) → `invalidateCache()`  
`_trash/` is skipped by `getNoteNames()` — files there are invisible to the app  
Recovery: manual file move [NOT IMPLEMENTED in UI]

---

## Cache Invalidation

`FileRepository.invalidateCache()` sets `cacheUpToDate = false` AND `cacheReviewUpToDate = false`  
Called automatically after every write (create, update, rename, delete)  
No HTTP endpoint to trigger manually — restart or any write op clears both caches

**CACHE CURRENTLY DISABLED** — `getNoteNames()` and `getReviewNotes()` ignore `cacheUpToDate` flags and always recompute. Re-enable by uncommenting the guard at the top of each method in `FileRepository.java` once the app is stable and the feedback loop is trusted.

---

## Infrastructure

Both services run in Docker via `docker-compose.yml` at the repo root.  
Start everything: `.\start.ps1` (or `docker compose up --build`)  
Stop everything: `Ctrl+C` in the same terminal → both containers stop cleanly

Config for friends: copy `.env.example` → `.env`, set `HOST_VAULT_PATH` to vault directory.  
Vault is mounted read-write at `/vault` inside the backend container.

### Technology Notes
- **WAR + embedded Tomcat**: `java -jar app.war` works because Spring Boot rewrites the WAR to be self-executable even though `spring-boot-starter-tomcat` is `provided` scope.
- **Volume mount on Windows (Docker Desktop)**: reads/writes work correctly; file watching (inotify/WatchService) does NOT propagate from host → container. This is why the backend uses a flag-based cache rather than a WatchService — WatchService would silently miss Obsidian edits when running in Docker.
- **Docker network**: frontend and backend are on the default compose network; nginx proxies `/api/*` → `backend:8084` by service name. No `host.docker.internal` needed.

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
| Protected vs public endpoints | `SecurityConfig.filterChain()` |

---

## Residual (next session)

- **Cross-file rename** — `FileRepository.updateLinksOnRename()` should read all .md files, replace `[[oldName]]` → `[[newName]]`, write back. Currently not implemented.
- **HTTP cache invalidation endpoint** — optional `/admin/invalidate` for cache clearing without restart
