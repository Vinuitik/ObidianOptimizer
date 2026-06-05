# Frontend Flows

Files: main.jsx, App.jsx, App.module.css, pages/MainPage.jsx, pages/SettingsPage.jsx, pages/ReviewPage.jsx, store/useStore.js, api/notes.js, env.js, utils/markdown.js

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

Route transitions: `AnimatePresence mode="wait"` + Framer Motion `motion.div` with `opacity 0→1 / 1→0` at 180ms  
To add a route: add to `NAV_ITEMS` in `NavBar.jsx` + add `<Route>` in `App.jsx` + create page component  
To change transition timing: `pageVariants` in `App.jsx`

---

## Navigation Bar

`NavBar.jsx` + `NavBar.module.css` — fixed top bar, 52px height  
Uses `NavLink` from react-router-dom — `.linkActive` class applied automatically on active route  
Auth state from Zustand: `isAuthenticated` → shows "Sign in" or "Sign out"  
Sign in: calls `setShowLogin(true)` → `LoginModal` rendered in `MainPage`  
Sign out: calls `store.logout()` → `POST /api/logout`

---

## Config (env.js)

`src/env.js` — single source of truth for all config values  
```
ENV.API_BASE        — base path for all API calls (default '/api')
ENV.PORTS           — FRONTEND:8083, BACKEND:8082, DEV:5173
ENV.FEATURES        — feature flags (REVIEW_PANEL, TRASH_RESTORE, CROSS_FILE_RENAME)
ENV.LIMITS          — ITEMS_PER_PAGE
```
`api/notes.js` imports `ENV.API_BASE` — change the URL in one place, all calls follow  
To change backend URL: `env.js` → `ENV.API_BASE`  
To toggle a feature: `env.js` → `ENV.FEATURES.<FLAG>`

---

## Design System

Tokens: `styles/tokens.css` — all CSS custom properties (colors, spacing, radii, fonts, transitions)  
Reset: `styles/reset.css` — box-sizing, base font, scrollbar  
Global: `styles/globals.css` — imports tokens + reset, stagger-in keyframe, markdown body styles  
Fonts (Google Fonts in `index.html`): Space Grotesk (headings) / DM Sans (body) / DM Mono (code/labels)  
Accent: indigo `#6c86f5` — `--color-accent` in tokens.css  
To change accent: `tokens.css` → `--color-accent` + `--color-accent-dim` + `--color-accent-border`  
To change any color/spacing/font: `tokens.css` only — never edit component CSS directly for tokens

### Technology Notes
- **Framer Motion**: used for page-level route transitions (`AnimatePresence` in `App.jsx`) and can be extended for center panel mode switches. State is not persisted — animations are purely visual.
- **Zustand**: in-memory only. State is lost on page refresh — auth check re-runs on every load via `checkAuth()`.
- **Google Fonts**: loaded via CDN link in `index.html`. Requires internet access at load time. If offline, falls back to system sans-serif. To bundle fonts instead, add them to `public/fonts/` and use `@font-face` in `tokens.css`.
- **CSS Modules**: each component owns its styles — class names are scoped automatically by Vite. Never use global class names inside `.module.css` files.

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

Credentials: set in `application.properties` → `app.auth.username` / `app.auth.password`  
Session: managed by Spring Security (in-memory, cookie-based)

---

## READ — Debug Logs

`openNote(fullPath)` in `useStore.js`:
```
console.log('[READ in  200]', raw.slice(0, 200))   // raw markdown from backend
console.log('[READ out 200]', html.slice(0, 200))  // rendered HTML
```
Open browser console and click any note to see both.

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

**RESIDUAL:** Cross-file `[[link]]` reference updating on rename [NOT IMPLEMENTED]

---

## DELETE (soft)

"Move to trash" button in `NoteEditor` → `window.confirm()` prompt  
→ `deleteNote(path)` → `DELETE /api/notes` → backend moves file to `ROOT/_trash/`  
→ `currentNotePath = null`, `centerMode = 'view'`, tree + review re-fetched  
Recovery: manual file move from `_trash/` back to vault [NOT IMPLEMENTED in UI]

---

## State (Zustand — useStore.js)

| Field | Purpose |
|---|---|
| `tree` | nested `{type, children, fullPath}` — vault file tree |
| `vaultRoot` | absolute path to vault root |
| `reviewNotes` | `[{shortName, fullPath}]` |
| `currentNoteHtml` | rendered HTML of open note |
| `currentNoteRaw` | raw markdown of open note |
| `currentNotePath` | absolute path of open note |
| `isAuthenticated` | `true` after successful login |
| `showLogin` | controls LoginModal visibility |
| `centerMode` | `'view' \| 'new' \| 'edit'` |
| `newNoteFolder` | folder path for new note creation |
| `leftCollapsed`, `rightCollapsed` | panel collapse state |

---

## API Layer (api/notes.js)

All calls prefixed by `ENV.API_BASE` (default `/api`) from `env.js`  
`ApiError` class carries `.status` — store actions check `e.status === 401` to show login modal  
Write calls use `credentials: 'same-origin'` so session cookie is included automatically

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

## Markdown Rendering (utils/markdown.js)

`renderMarkdown(content)`:
1. Replace `![[image.png]]` → `<img src="/api/images/image.png" class="embedded-image">`
2. Replace `[[link text]]` → plain `link text`
3. `markdown-it.render()` → HTML string

**RESIDUAL — known rendering issues:**
- YAML frontmatter (`---` block) renders as plain text — needs stripping before render
- Hashtags (e.g. `#tag`) not highlighted
- Table rendering not confirmed working
- No Obsidian-style embedded note preview

---

## Responsive

- Mobile (≤600px): NavBar links shrink, padding reduced
- Tablet (≤768px): Left and right sidebars hidden (`display:none` in `SplitLayout.module.css`)
- Center panel always visible — full width on mobile

---

## Infrastructure

`docker-compose.yml` → `frontend` service on `localhost:8083`  
`frontend/Dockerfile` — Node 20 build → Nginx alpine serve  
`frontend/nginx.conf` — `/api/*` → `host.docker.internal:8082`, `/` → SPA  
`start.ps1` — Java in new window + `docker compose up --build`

Ports (also in `env.js`):
- `http://localhost:8083` — React app via Nginx (Docker)
- `http://localhost:8082` — Java backend
- `http://localhost:5173` — Dev (Vite proxy)

---

## Change Index

| Thing to change | Where |
|---|---|
| API base URL | `env.js` `ENV.API_BASE` |
| Feature flags | `env.js` `ENV.FEATURES` |
| Auth credentials | `application.properties` |
| Session config | `SecurityConfig.java` |
| Vault root path | `FileRepository.java` `ROOT_FILE` |
| Design tokens (colors, fonts, spacing) | `styles/tokens.css` |
| Accent color | `styles/tokens.css` `--color-accent` + `--color-accent-dim` + `--color-accent-border` |
| Google Fonts | `index.html` font link + `tokens.css` `--font-*` vars |
| Page routes | `App.jsx` `<Routes>` + `NavBar.jsx` `NAV_ITEMS` |
| Route transition speed | `App.jsx` `pageVariants` |
| Center panel mode routing | `SplitLayout.jsx` |
| Note editor UI | `NoteEditor.jsx` / `NoteEditor.module.css` |
| New note form UI | `NewNoteForm.jsx` / `NewNoteForm.module.css` |
| Login modal UI | `LoginModal.jsx` / `LoginModal.module.css` |
| Markdown rendering | `utils/markdown.js` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |

---

## Residual (next session)

- **Markdown rendering fixes** — strip frontmatter, hashtag colouring, confirm table rendering
- **Cross-file rename** — update `[[oldName]]` → `[[newName]]` in all vault notes
- **Trash UI** — list and restore notes from `_trash/`
- **Review page** — build out `ReviewPage.jsx` (currently a stub)
- **Settings page** — build out `SettingsPage.jsx` (currently a stub)
