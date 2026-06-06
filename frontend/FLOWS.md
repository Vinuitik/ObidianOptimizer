# Frontend Flows

Files: main.jsx, App.jsx, App.module.css, pages/MainPage.jsx, pages/SettingsPage.jsx, pages/ReviewPage.jsx, store/useStore.js, api/notes.js, env.js, utils/markdown.js, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, molecules/SearchBar.jsx

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

`NavBar.jsx` + `NavBar.module.css` — fixed top bar, 56px height  
Brand: `ObsidianMark(22px glow=false)` + "Obsidian **Optimizer**" (Optimizer in `--color-accent`)  
Right side: streak chip (flame icon + "12-day streak" — hardcoded) + 28px avatar tile ("V")  
Uses `NavLink` from react-router-dom — `.linkActive` class applied automatically on active route  
Auth state from Zustand: `isAuthenticated` → shows "Sign in" or "Sign out"  
Sign in: calls `setShowLogin(true)` → `LoginModal` rendered in `MainPage`  
Sign out: calls `store.logout()` → `POST /api/logout`  
To wire streak dynamically: add `streakDays` to store, pass to NavBar

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
Accent: amethyst `#7c5cff` — `--color-accent` in tokens.css  
Accent soft: `--color-accent-soft: #a68dff` (lighter amethyst for text on dark bg)  
Accent dim: `--color-accent-dim: rgba(124,92,255,0.14)` (tinted backgrounds)  
Accent border: `--color-accent-border: rgba(124,92,255,0.34)` (bordered highlights)  
Semantic additions: `--color-amber: #e0a458` (streak), `--color-success: #4cc38a`  
Gap-fill radii: `--radius-xs: 8px`, `--radius-sm2: 9px`, `--radius-md2: 11px`, `--radius-lg2: 12px`  
To change accent: `tokens.css` → `--color-accent` + `--color-accent-soft` + `--color-accent-dim` + `--color-accent-border`  
To change any color/spacing/font: `tokens.css` only — never edit component CSS directly for tokens

### New atoms (Redesign A)

`atoms/Icon.jsx` — SVG glyph set via name prop (folder, file, chevron, search, plus, sparkle, clock, flame, dot, check, link, settings). `paths` lookup at module scope — no per-render alloc. Props: `name`, `size`, `color`, `strokeWidth`  
`atoms/ObsidianMark.jsx` — pure-CSS faceted obsidian shard mark. Facet data at module scope. Props: `size` (default 120), `glow` (default true). Use `glow=false` in nav for no drop-shadow  
`atoms/Chip.jsx` + `Chip.module.css` — inline action chip. Renders as `<span>` (static) or `<button>` (if `onClick` passed). Props: `children`, `onClick`, `className`  
`atoms/Ring.jsx` — SVG progress ring. Props: `pct` (0-100), `size` (default 44), `strokeWidth` (default 4)

### New molecules (Redesign A)

`molecules/SearchBar.jsx` + `SearchBar.module.css` — controlled search input with search Icon, ⌘K / Ctrl+K focus shortcut (global keydown listener), clear button when query is non-empty. Props: `value`, `onChange`

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

## FolderTree search filter

`FolderTree.jsx` — local `query` state, passed to all `TreeNode` instances  
`SearchBar` rendered above new-note button; on change updates `query`  
⌘K / Ctrl+K globally focuses the search input (listener in `SearchBar.jsx`)  
`hasMatch(name, node, query)` — recursive: file = substring match on display name; folder = any descendant file matches  
When query is active: folders without matching descendants hidden; folders with matches force-open; non-matching files hidden  
"No results" empty state shown if all root entries filtered  
`isActive` on file NavItem: `currentNotePath === node.fullPath` — drives accent-dim background + 3px left accent bar  
To add AI-note sparkle badge: pass `isAI={true}` to NavItem — shows trailing sparkle icon

## CREATE

`+` button on folder hover (NavItem) → `startNewNote(folderPath)` → `centerMode = 'new'`, `newNoteName = ''`  
"New note" at top of FolderTree → `startNewNote(vaultRoot)` (creates at vault root)  
Center header becomes an `<input>` (`.headerTitleInput` in `SplitLayout.module.css`) — user types name there  
Enter in header input or "Create" button in `NewNoteForm` → `createNote(folder, newNoteName)`  
→ `POST /api/notes` → `{ path: absolutePath }` → `openNote(path)` → view mode

Name state: `newNoteName` in Zustand store, set via `setNewNoteName()`, reset on `startNewNote`/`cancelNewNote`  
`NewNoteForm` is now just folder hint + Create/Cancel — no name input of its own  
Backend: creates `folder/name.md` with frontmatter `sr-due: today+3d`, `sr-interval: 3`, `sr-ease: 200`  
To change initial frontmatter: `FileRepository.createNote()`

---

## UPDATE (content + rename)

"Edit" button in center header → `centerMode = 'edit'`  
`NoteEditor` renders with `currentNoteRaw` as textarea content, `currentNotePath` filename as title  
User edits title and/or content → "Save"  
`saveNote(title, content)` in store:
1. If title changed → `PATCH /api/notes/rename` → get new path
2. `computeHunks(currentNoteRaw, content)` [utils/diff.js] — LCS diff, CRLF-normalized
3. If hunks non-empty → `PATCH /api/notes/content` with `{ path, hunks }`
4. If hunks empty (no content change) → skip the network call
5. Re-render note from new content, `centerMode = 'view'`

Diff algorithm: `utils/diff.js lcsBacktrack()` — O(m×n) LCS backtrack → edit ops → compressed hunks  
Hunk shape: `{ startLine: number, deleteCount: number, insertLines: string[] }`  
CRLF handling: both sides normalized with `.replace(/\r\n/g, '\n')` before diff — browser edits are LF, server files may be CRLF  
Backend applies hunks back-to-front, preserves original line separator, validated with bounds check  
To change diff algorithm: `utils/diff.js lcsBacktrack()`  
To revert to full-replace: call `apiUpdate(path, content)` instead of `apiPatch` in `useStore.js saveNote()`

**RESIDUAL:** Cross-file `[[link]]` reference updating on rename [NOT IMPLEMENTED]

---

## DELETE (soft)

Three entry points, all call `deleteNote(path)` → `DELETE /api/notes` → backend moves file to `ROOT/_trash/`

| Where | How to reach |
|---|---|
| Left panel tree | Hover any file → 🗑 icon appears (hidden by default) → `window.confirm` → delete |
| Center header | Open a note in view mode → 🗑 button next to "Edit" → `window.confirm` → delete |
| Edit mode | "Edit" → "Move to trash" button at bottom of `NoteEditor` → `window.confirm` → delete |

After delete: `currentNotePath = null`, `centerMode = 'view'`, tree + review re-fetched  
Recovery: manual file move from `_trash/` back to vault [NOT IMPLEMENTED in UI]  
To change trash icon style: `NavItem.module.css` `.deleteBtn`  
To change center-header button style: `SplitLayout.jsx` danger `Button` next to Edit

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
1. `parseFrontmatter()` — extracts `---` YAML block, renders it as an HTML `<table>` (key column = `<th scope="row">`, value column = `<td>`); prepended before note body
2. `![[image.png]]` → `<img src="/api/images/image.png" class="embedded-image">`
3. `[[link|alias]]` or `[[link]]` → `<a class="wiki-link" data-wiki-link="link" href="#">label</a>`
4. `#hashtag` → `<span class="md-tag">#tag</span>` (indigo pill, skips `# Headings`)
5. `markdown-it.render()` with `linkify: true`, `typographer: true`, `breaks: true`, tables enabled

`breaks: true` — single newlines in the markdown body become `<br>` (Obsidian-style soft wrapping).  
Frontmatter table uses `.markdown-body th/td` styles from `globals.css` — keys appear with card background + mono font.

`.wiki-link` and `.md-tag` styled in `styles/globals.css`

**RESIDUAL:**
- No Obsidian-style embedded note preview
- Images served via `/api/images/` — path must match `ImageRepository.imageDir` hardcoded path

---

## Wiki-link Resolution (NoteViewer.jsx + useStore.js)

`noteIndex` — `Map<string, string>` built in `fetchNoteNames()`:
- Key: basename lowercased, no `.md` extension (e.g. `"agents 2025"`)
- Value: full absolute path (e.g. `C:\...\Agents 2025.md`)

Click on `.wiki-link` → `NoteViewer` intercepts via event delegation on the container div:
1. `data-wiki-link` attribute holds the **original target string** from markdown (e.g. `"Books/LikeSwitcher"`)
2. Lookup: try full target lowercased first → fall back to basename (handles both `[[Note]]` and `[[Folder/Note]]`)
3. If resolved: `openNote(fullPath)` — loads the note
4. If not found: silently does nothing (dead link, note doesn't exist in vault)

---

## Write Roundtrip — Obsidian Compatibility

**Critical invariant:** the app edits raw markdown, never the rendered HTML.

Flow:
```
Backend disk  →  GET /api/text  →  currentNoteRaw (raw .md string, unchanged)
                                 ↓
                           renderMarkdown()  →  currentNoteHtml (display only)
                                 ↓
                        NoteEditor textarea  ←  currentNoteRaw
                                 ↓
                     PUT /api/notes (currentNoteRaw back to disk, unchanged)
```

`[[links]]`, `![[images]]`, frontmatter, and all Obsidian syntax are **preserved verbatim** in `currentNoteRaw` and written back to disk exactly as Obsidian left them. The rendered HTML is display-only and never saved.

`data-wiki-link` stores the full original link target (e.g. `"Books/LikeSwitcher"`) so if a future WYSIWYG editor ever needs to reconstruct `[[Books/LikeSwitcher]]` from the HTML, the information is there.

To change the editor: `NoteEditor.jsx` — it reads `currentNoteRaw` and writes back plain text. Do NOT switch to saving `currentNoteHtml` — that would destroy Obsidian syntax.

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

**BROWSER CACHING DISABLED** — `nginx.conf` sends `Cache-Control: no-store, no-cache, must-revalidate` globally; all fetch calls in `api/notes.js` pass `cache: 'no-store'`. Re-enable by removing the `add_header` line in nginx and the `cache` option from fetch calls once the app is stable.

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
| Accent color | `styles/tokens.css` `--color-accent` + `--color-accent-soft` + `--color-accent-dim` + `--color-accent-border` |
| Logo mark (size/glow) | `atoms/ObsidianMark.jsx` |
| Icon glyphs | `atoms/Icon.jsx` `paths` object |
| Streak display | `NavBar.jsx` hardcoded "12-day streak" — wire to store when backend supports it |
| Search filter logic | `FolderTree.jsx` `hasMatch()` |
| Review card layout | `ReviewList.jsx` + `ReviewList.module.css` |
| Due count in panel header | `SplitLayout.jsx` `dueSlot` — reads `reviewNotes.length` |
| Google Fonts | `index.html` font link + `tokens.css` `--font-*` vars |
| Page routes | `App.jsx` `<Routes>` + `NavBar.jsx` `NAV_ITEMS` |
| Route transition speed | `App.jsx` `pageVariants` |
| Center panel mode routing | `SplitLayout.jsx` |
| Note editor UI | `NoteEditor.jsx` / `NoteEditor.module.css` |
| New note header input style | `SplitLayout.module.css` `.headerTitleInput` |
| New note form (folder hint + buttons) | `NewNoteForm.jsx` / `NewNoteForm.module.css` |
| Diff algorithm | `utils/diff.js lcsBacktrack()` |
| Login modal UI | `LoginModal.jsx` / `LoginModal.module.css` |
| Markdown rendering | `utils/markdown.js` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |

---

## Auth — protected reads

All `/api/**` endpoints now require authentication (including `/names`, `/review`, `/text`).  
Unauthenticated users see empty tree and empty review list.  
After login, `fetchNoteNames()` + `fetchReviewNotes()` are called automatically to populate the UI.  
To change which endpoints are public: `SecurityConfig.java` `authorizeHttpRequests`

## Resizable Panels (SplitLayout.jsx)

Left and right panels are draggable via a 4px `.resizeHandle` div between each panel and the center.  
`useResize(initialWidth)` hook: tracks `mousedown` → global `mousemove/mouseup` → updates width state.  
Width applied via inline `style={{ width, minWidth }}` on the panel div.  
Collapsed state overrides via `!important` in CSS (`.collapsed` class).  
Constants: `MIN_PANEL_WIDTH = 160`, `MAX_PANEL_WIDTH = 480`, `DEFAULT_WIDTH = 290`  
To change default panel size: `SplitLayout.jsx` `DEFAULT_WIDTH`

## Residual (next session)

- **Cross-file rename** — update `[[oldName]]` → `[[newName]]` in all vault notes
- **Trash UI** — list and restore notes from `_trash/`
- **Review page** — build out `ReviewPage.jsx` (currently a stub)
- **Settings page** — build out `SettingsPage.jsx` (currently a stub)
