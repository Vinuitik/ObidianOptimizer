# Frontend Flows

Files: main.jsx, App.jsx, pages/MainPage.jsx, store/useStore.js, api/notes.js, utils/markdown.js

---

## Startup

`main.jsx` → `createRoot` → `<App>` → `<MainPage>`  
`MainPage` `useEffect` → parallel: `checkAuth()` + `fetchNoteNames()` + `fetchReviewNotes()` → store updated → render

---

## Center Panel Modes (`centerMode` in store)

| Mode | Trigger | Renders |
|---|---|---|
| `view` | default / after open/save/cancel | `NoteViewer` |
| `new` | click `+` on folder or "New note" | `NewNoteForm` |
| `edit` | click "Edit" in center header | `NoteEditor` |

To change mode routing: `SplitLayout.jsx` center panel conditional render

---

## Auth Flow

`MainPage` → `checkAuth()` → `GET /api/me`  
- 200: `isAuthenticated = true` → "Sign out" button visible  
- 401: `isAuthenticated = false` → "Sign in" button visible

Write action fails with 401 → `set({ showLogin: true })` → `LoginModal` renders over the app  
Login form → `POST /api/login` (form-encoded) → Spring Security session cookie set  
`isAuthenticated = true`, `showLogin = false` → user retries action  
Logout → `POST /api/logout` → `isAuthenticated = false`

Credentials: set in `application.properties` → `app.auth.username` / `app.auth.password`  
Session: managed by Spring Security (in-memory, cookie-based)

---

## READ — Debug Logs

`openNote(fullPath)` in `useStore.js`:
```
console.log('[READ in  200]', raw.slice(0, 200))   // raw markdown from backend
console.log('[READ out 200]', html.slice(0, 200))  // rendered HTML
```
Open browser console and click any note to see both. Use these to diagnose rendering issues.

---

## CREATE

`+` button on folder hover (NavItem) → `startNewNote(folderPath)` → `centerMode = 'new'`  
"New note" at top of FolderTree → `startNewNote(vaultRoot)` (creates at vault root)  
`NewNoteForm` renders → user types name → Enter or "Create" button  
→ `createNote(folder, name)` → `POST /api/notes` → `{ path: absolutePath }`  
→ `fetchNoteNames()` rebuilds tree → `openNote(path)` → view mode

Backend: creates file at `folder/name.md` with minimal frontmatter (`reviewed: today`)  
To change initial frontmatter: `FileRepository.createNote()`

---

## UPDATE (content + rename)

"Edit" button in center header → `centerMode = 'edit'`  
`NoteEditor` renders with `currentNoteRaw` as textarea content, `currentNotePath` filename as title  
User edits title and/or content → "Save"  
`saveNote(title, content)` in store:
1. If title changed → `PATCH /api/notes/rename` → get new path
2. `PUT /api/notes` with (new path, content)
3. Re-render note from new content, `centerMode = 'view'`
4. `fetchNoteNames()` + `fetchReviewNotes()` to sync tree

**RESIDUAL:** Cross-file `[[link]]` reference updating. When a note is renamed, other notes that  
contain `[[oldName]]` are NOT updated. This is a known limitation. Must be implemented in backend  
(`FileRepository.updateLinksOnRename()`) by reading all .md files and rewriting matching links.

---

## DELETE (soft)

"Move to trash" button in `NoteEditor` → `window.confirm()` prompt  
→ `deleteNote(path)` → `DELETE /api/notes` → backend moves file to `ROOT/_trash/`  
→ `currentNotePath = null`, `centerMode = 'view'`, tree + review re-fetched  
Trash directory is skipped in `getNoteNames()` — notes there are invisible to the app  
Recovery: manual file move from `_trash/` back to vault [NOT IMPLEMENTED in UI]

---

## State (Zustand — useStore.js)

| Field | Purpose |
|---|---|
| `tree` | nested `{type, children, fullPath}` — vault file tree |
| `vaultRoot` | absolute path to vault root (for creating at root level) |
| `reviewNotes` | `[{shortName, fullPath}]` |
| `currentNoteHtml` | rendered HTML of open note |
| `currentNoteRaw` | raw markdown of open note (used to init NoteEditor) |
| `currentNotePath` | absolute path of open note |
| `isAuthenticated` | `true` after successful login |
| `showLogin` | controls LoginModal visibility |
| `centerMode` | `'view' \| 'new' \| 'edit'` |
| `newNoteFolder` | absolute path of folder for new note creation |
| `leftCollapsed`, `rightCollapsed` | panel UI state |

---

## API Layer (api/notes.js)

All calls prefixed `/api/` — Vite dev proxy or Nginx strips `/api` and forwards to `:8082`  
`ApiError` class carries `.status` — store actions check `e.status === 401` to show login modal  
Write calls use `credentials: 'same-origin'` so session cookie is included automatically

| Function | Method | Auth required |
|---|---|---|
| `fetchNames()` | `GET /api/names` | No |
| `fetchReview()` | `GET /api/review` | No |
| `fetchNoteContent(path)` | `GET /api/text?noteName=` | No |
| `checkAuth()` | `GET /api/me` | — (returns bool) |
| `login(u, p)` | `POST /api/login` | No |
| `logout()` | `POST /api/logout` | No |
| `createNote(folder, name)` | `POST /api/notes` | Yes |
| `updateNote(path, content)` | `PUT /api/notes` | Yes |
| `renameNote(oldPath, name)` | `PATCH /api/notes/rename` | Yes |
| `deleteNote(path)` | `DELETE /api/notes` | Yes |

---

## Markdown Rendering (utils/markdown.js)

`renderMarkdown(content)`:
1. Replace `![[image.png]]` → `<img src="/api/images/image.png" class="embedded-image">`
2. Replace `[[link text]]` → plain `link text`
3. `markdown-it.render()` → HTML string

**RESIDUAL — known rendering issues (awaiting user debug output):**
- YAML frontmatter (`---` block) renders as plain text — needs stripping before render
- Hashtags (e.g. `#tag`) not highlighted
- Table rendering not confirmed working (may need `markdown-it` options)
- No Obsidian-style embedded note preview

---

## Infrastructure

`docker-compose.yml` → `frontend` service on `localhost:8083`  
`frontend/Dockerfile` — Node 20 build → Nginx alpine serve  
`frontend/nginx.conf` — `/api/*` → `host.docker.internal:8082`, `/` → SPA  
`start.ps1` — Java in new window + `docker compose up --build`  

Ports:
- `http://localhost:8083` — React app via Nginx (Docker)
- `http://localhost:8082` — Java backend (also serves old static UI as fallback)

Dev: `cd frontend && npm run dev` → `http://localhost:5173` (Vite proxy handles `/api/`)

---

## Change Index

| Thing to change | Where |
|---|---|
| Auth credentials | `application.properties` `app.auth.username` / `app.auth.password` |
| Session config | `SecurityConfig.java` |
| Protected endpoints | `SecurityConfig.java` `authorizeHttpRequests` |
| Vault root path | `FileRepository.java` `ROOT_FILE` |
| Trash directory | `FileRepository.softDeleteNote()` |
| Initial note frontmatter | `FileRepository.createNote()` |
| Center panel mode routing | `SplitLayout.jsx` |
| Note editor UI | `NoteEditor.jsx` / `NoteEditor.module.css` |
| New note form UI | `NewNoteForm.jsx` / `NewNoteForm.module.css` |
| Login modal UI | `LoginModal.jsx` / `LoginModal.module.css` |
| Obsidian syntax rendering | `utils/markdown.js` |
| Design tokens | `styles/globals.css` `:root` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |

---

## Residual (next session)

- **Markdown rendering fixes** — strip frontmatter, hashtag colouring, confirm table rendering (need console log output from user first)
- **Cross-file rename** — when note renamed, update `[[oldName]]` → `[[newName]]` in all vault notes (`FileRepository.updateLinksOnRename()`)
- **Trash UI** — list and restore notes from `_trash/`
- **Color scheme** — shift toward purple per user preference
