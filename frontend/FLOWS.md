# Frontend Flows

Files: main.jsx, App.jsx, App.module.css, pages/MainPage.jsx, pages/SettingsPage.jsx, pages/ReviewPage.jsx, store/useStore.js, api/notes.js, env.js, utils/diff.js, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, molecules/SearchBar.jsx

Design system, state shape, and markdown rendering → [DESIGN.md](DESIGN.md)

---

## Startup

`main.jsx` → `createRoot` → `<App>` → `<BrowserRouter>` → `<NavBar>` + `<AnimatedRoutes>`  
`AnimatedRoutes` wraps all `<Routes>` in Framer Motion `AnimatePresence` — 180ms opacity fade on route change  
`MainPage` `useEffect` → parallel: `checkAuth()` + `fetchNoteNames()` + `fetchReviewNotes()` → store updated → render

---

## Routing

| Path | Component | Notes |
|---|---|---|
| `/` | `MainPage` | 3-panel note editor |
| `/review` | `ReviewPage` | Dedicated review queue [stub] |
| `/settings` | `SettingsPage` | Vault settings [stub] |

Route transitions: `AnimatePresence mode="wait"` + Framer Motion `motion.div` `opacity 0→1 / 1→0` at 180ms  
To add a route: add to `NAV_ITEMS` in `NavBar.jsx` + add `<Route>` in `App.jsx` + create page component  
To change transition timing: `pageVariants` in `App.jsx`

---

## Navigation Bar

`NavBar.jsx` + `NavBar.module.css` — fixed top bar, 56px height  
Auth state from Zustand `isAuthenticated` → shows "Sign in" or "Sign out"  
Sign in: `setShowLogin(true)` → `LoginModal` rendered in `MainPage`  
Sign out: `store.logout()` → `POST /api/logout`  
Uses `NavLink` from react-router-dom — `.linkActive` applied automatically on active route  
To wire streak dynamically: add `streakDays` to store, pass to `NavBar`

---

## Config (env.js)

`src/env.js` — single source of truth for all config values
```
ENV.API_BASE    — base path for all API calls (default '/api')
ENV.PORTS       — FRONTEND:8083, BACKEND:8082, DEV:5173
ENV.FEATURES    — feature flags (REVIEW_PANEL, TRASH_RESTORE, CROSS_FILE_RENAME)
ENV.LIMITS      — ITEMS_PER_PAGE
```
`api/notes.js` imports `ENV.API_BASE` — change URL in one place, all calls follow  
To change backend URL: `env.js` → `ENV.API_BASE`  
To toggle a feature: `env.js` → `ENV.FEATURES.<FLAG>`

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
- 200: `isAuthenticated = true` → "Sign out" in NavBar
- 401: `isAuthenticated = false` → "Sign in" in NavBar

Write action fails with 401 → `set({ showLogin: true })` → `LoginModal` renders over the app  
Login form → `POST /api/login` (form-encoded) → Spring Security session cookie set  
`isAuthenticated = true`, `showLogin = false` → user retries action  
Logout → `POST /api/logout` → `isAuthenticated = false`

Credentials: `application.properties` → `app.auth.username` / `app.auth.password`  
Session: Spring Security in-memory, cookie-based

---

## READ — Debug Logs

`openNote(fullPath)` in `useStore.js`:
```
console.log('[READ in  200]', raw.slice(0, 200))   // raw markdown from backend
console.log('[READ out 200]', html.slice(0, 200))  // rendered HTML
```
Open browser console and click any note to see both.

---

## FolderTree Search Filter

`FolderTree.jsx` — local `query` state, passed to all `TreeNode` instances  
`SearchBar` rendered above new-note button; on change updates `query`  
⌘K / Ctrl+K globally focuses the search input (listener in `SearchBar.jsx`)  
`hasMatch(name, node, query)` — recursive: file = substring match on display name; folder = any descendant file matches  
When query active: folders without matching descendants hidden; folders with matches force-open; non-matching files hidden  
"No results" empty state shown if all root entries filtered  
`isActive` on file NavItem: `currentNotePath === node.fullPath` — drives accent-dim background + 3px left accent bar  
To add AI-note sparkle badge: pass `isAI={true}` to `NavItem` — shows trailing sparkle icon

---

## CREATE

`+` button on folder hover → `startNewNote(folderPath)` → `centerMode = 'new'`, `newNoteName = ''`  
"New note" at top of FolderTree → `startNewNote(vaultRoot)` (creates at vault root)  
Center header becomes an `<input>` — user types name there  
Enter in header input or "Create" button in `NewNoteForm` → `createNote(folder, newNoteName)`  
→ `POST /api/notes` → `{ path: absolutePath }` → `openNote(path)` → view mode

`newNoteName` in Zustand store, set via `setNewNoteName()`, reset on `startNewNote`/`cancelNewNote`  
`NewNoteForm` is folder hint + Create/Cancel — no name input of its own  
Backend: creates `folder/name.md` with frontmatter `sr-due: today+3d`, `sr-interval: 3`, `sr-ease: 200`  
To change initial frontmatter: `FileRepository.createNote()`

---

## UPDATE (content + rename)

"Edit" button in center header → `centerMode = 'edit'`  
`NoteEditor` renders with `currentNoteRaw` as textarea content, `currentNotePath` filename as editable title  
User edits title and/or content → "Save"  
`saveNote(title, content)` in store:
1. If title changed → `PATCH /api/notes/rename` → get new path
2. `computeHunks(currentNoteRaw, content)` [utils/diff.js] — LCS diff, CRLF-normalized
3. If hunks non-empty → `PATCH /api/notes/content` with `{ path, hunks }`
4. If hunks empty (no content change) → skip the network call
5. Re-render note from new content, `centerMode = 'view'`

Diff: `utils/diff.js lcsBacktrack()` — O(m×n) LCS → edit ops → compressed hunks  
Hunk shape: `{ startLine: number, deleteCount: number, insertLines: string[] }`  
CRLF handling: both sides normalized with `.replace(/\r\n/g, '\n')` — browser edits are LF, server files may be CRLF  
Backend applies hunks back-to-front, preserves original line separator  
To change diff algorithm: `utils/diff.js lcsBacktrack()`  
To revert to full-replace: call `apiUpdate(path, content)` instead of `apiPatch` in `useStore.js saveNote()`

**RESIDUAL:** Cross-file `[[link]]` reference updating on rename [NOT IMPLEMENTED]

---

## DELETE (soft)

Three entry points, all call `deleteNote(path)` → `DELETE /api/notes` → backend moves file to `ROOT/_trash/`

| Where | How to reach |
|---|---|
| Left panel tree | Hover any file → 🗑 icon appears → `window.confirm` → delete |
| Center header | Open note in view mode → 🗑 button next to "Edit" → `window.confirm` → delete |
| Edit mode | "Edit" → "Move to trash" at bottom of `NoteEditor` → `window.confirm` → delete |

After delete: `currentNotePath = null`, `centerMode = 'view'`, tree + review re-fetched  
Recovery: manual file move from `_trash/` back to vault [NOT IMPLEMENTED in UI]

---

## API Layer (api/notes.js)

All calls prefixed by `ENV.API_BASE` (default `/api`)  
`ApiError` class carries `.status` — store actions check `e.status === 401` to show login modal  
Write calls use `credentials: 'same-origin'` — session cookie included automatically

| Function | Method | Auth required |
|---|---|---|
| `fetchNames()` | `GET /api/names` | No |
| `fetchReview()` | `GET /api/review` | No |
| `fetchNoteContent(path)` | `GET /api/text?noteName=` | No |
| `checkAuth()` | `GET /api/me` | — |
| `login(u, p)` | `POST /api/login` | No |
| `logout()` | `POST /api/logout` | No |
| `createNote(folder, name)` | `POST /api/notes` | Yes |
| `updateNote(path, content)` | `PUT /api/notes` | Yes |
| `renameNote(oldPath, name)` | `PATCH /api/notes/rename` | Yes |
| `deleteNote(path)` | `DELETE /api/notes` | Yes |

---

## Wiki-link Resolution (NoteViewer.jsx + useStore.js)

`noteIndex` — `Map<string, string>` built in `fetchNoteNames()`:
- Key: basename lowercased, no `.md` extension (e.g. `"agents 2025"`)
- Value: full absolute path

Click on `.wiki-link` → `NoteViewer` intercepts via event delegation on the container div:
1. `data-wiki-link` holds the original target string (e.g. `"Books/LikeSwitcher"`)
2. Lookup: try full target lowercased → fall back to basename (handles both `[[Note]]` and `[[Folder/Note]]`)
3. If resolved: `openNote(fullPath)`
4. If not found: silently does nothing (dead link)

---

## Write Roundtrip — Obsidian Compatibility

**Critical invariant:** the app edits raw markdown, never the rendered HTML.

```
Backend disk  →  GET /api/text  →  currentNoteRaw (raw .md string, unchanged)
                                 ↓
                           renderMarkdown()  →  currentNoteHtml (display only)
                                 ↓
                        NoteEditor textarea  ←  currentNoteRaw
                                 ↓
                     PUT /api/notes (currentNoteRaw back to disk, unchanged)
```

`[[links]]`, `![[images]]`, frontmatter, all Obsidian syntax preserved verbatim in `currentNoteRaw`.  
`data-wiki-link` stores the full original link target so future editors can reconstruct the link.  
To change the editor: `NoteEditor.jsx` — reads `currentNoteRaw`, writes back plain text. Do NOT switch to saving `currentNoteHtml`.

---

## Auth — Protected Reads

All `/api/**` endpoints require authentication (including `/names`, `/review`, `/text`).  
After login, `fetchNoteNames()` + `fetchReviewNotes()` called automatically to populate UI.  
To change which endpoints are public: `SecurityConfig.java` `authorizeHttpRequests`

---

## Resizable Panels (SplitLayout.jsx)

Left and right panels draggable via a 4px `.resizeHandle` div between each panel and center.  
`useResize(initialWidth)` hook: `mousedown` → global `mousemove/mouseup` → updates width state.  
Width applied via inline `style={{ width, minWidth }}`.  
Collapsed state overrides via `!important` in CSS (`.collapsed` class).  
Constants: `MIN_PANEL_WIDTH = 160`, `MAX_PANEL_WIDTH = 480`, `DEFAULT_WIDTH = 290`  
To change default panel size: `SplitLayout.jsx` `DEFAULT_WIDTH`

---

## Infrastructure

`docker-compose.yml` → `frontend` service on `localhost:8083`  
`frontend/Dockerfile` — Node 20 build → Nginx alpine serve  
`frontend/nginx.conf` — `/api/*` → `host.docker.internal:8082`, `/` → SPA  
`start.ps1` — Java in new window + `docker compose up --build`

**BROWSER CACHING DISABLED** — `nginx.conf` sends `Cache-Control: no-store` globally; all fetch calls pass `cache: 'no-store'`. Re-enable by removing the `add_header` line in nginx and the `cache` option from fetch calls.

Ports: `8083` → React (Nginx/Docker), `8082` → Java backend, `5173` → Dev (Vite proxy)

---

## Change Index

| Thing to change | Where |
|---|---|
| API base URL | `env.js` `ENV.API_BASE` |
| Feature flags | `env.js` `ENV.FEATURES` |
| Auth credentials | `application.properties` |
| Session config | `SecurityConfig.java` |
| Vault root path | `FileRepository.java` `ROOT_FILE` |
| Streak display | `NavBar.jsx` hardcoded "12-day streak" — wire to `streakDays` in store when backend supports it |
| Search filter logic | `FolderTree.jsx` `hasMatch()` |
| Review card layout | `ReviewList.jsx` + `ReviewList.module.css` |
| Due count in panel header | `SplitLayout.jsx` `dueSlot` — reads `reviewNotes.length` |
| Page routes | `App.jsx` `<Routes>` + `NavBar.jsx` `NAV_ITEMS` |
| Route transition speed | `App.jsx` `pageVariants` |
| Center panel mode routing | `SplitLayout.jsx` |
| Note editor UI | `NoteEditor.jsx` / `NoteEditor.module.css` |
| New note form | `NewNoteForm.jsx` / `NewNoteForm.module.css` |
| Diff algorithm | `utils/diff.js lcsBacktrack()` |
| Login modal UI | `LoginModal.jsx` / `LoginModal.module.css` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |

---

## Residual (next session)

- **Cross-file rename** — update `[[oldName]]` → `[[newName]]` in all vault notes
- **Trash UI** — list and restore notes from `_trash/`
- **Review page** — build out `ReviewPage.jsx` (currently a stub)
- **Settings page** — build out `SettingsPage.jsx` (currently a stub)
