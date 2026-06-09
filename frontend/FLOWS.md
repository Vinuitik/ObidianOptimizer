# Frontend Flows

Files: main.jsx, App.jsx, App.module.css, pages/MainPage.jsx, pages/SettingsPage.jsx, pages/ReviewPage.jsx, store/useStore.js, api/notes.js, env.js, utils/diff.js, utils/frontmatter.js, utils/wikiLinkPlugin.js, utils/obsidianImagePlugin.js, utils/hashtagPlugin.js, utils/livePreviewPlugin.js, utils/mathPlugin.js, utils/markdownCleanup.js, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, atoms/Button.jsx, molecules/SearchBar.jsx, molecules/FrontmatterTable.jsx, molecules/PanelHeader.jsx, molecules/NavItem.jsx, molecules/ReviewRating.jsx, molecules/TabBar.jsx, organisms/FolderTree.jsx, organisms/MilkdownEditor.jsx, organisms/NewNoteForm.jsx, organisms/ReviewList.jsx, organisms/NavBar.jsx, organisms/LoginModal.jsx, templates/SplitLayout.jsx

Design system, state shape, and markdown rendering → [DESIGN.md](DESIGN.md)

---

## Startup

`main.jsx` → `createRoot` → `<App>` → `<BrowserRouter>` → `<NavBar>` + `<AnimatedRoutes>`  
`AnimatedRoutes` wraps all `<Routes>` in Framer Motion `AnimatePresence` — 180ms opacity fade on route change  
`MainPage` `useEffect` → parallel: `checkAuth()` + `fetchRootChildren()` + `fetchNoteNames()` + `initReviewSession()` → store updated → render

---

## Routing

| Path | Component | Notes |
|---|---|---|
| `/` | `MainPage` | 3-panel WYSIWYG note editor |
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
To wire streak dynamically: add `streakDays` to store, pass to `NavBar` (currently hardcoded "12-day streak")

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

`centerMode` has two values: `'view'` (default) and `'new'` (new note form).  
Edit mode is no longer a separate `centerMode` — it is conveyed by the `isMutable` boolean.

| Mode / State | Trigger | What renders in center |
|---|---|---|
| `centerMode: 'view'`, `isMutable: false` | default / after open, save, cancel | `MilkdownEditor` (read-only) |
| `centerMode: 'view'`, `isMutable: true` | click "Edit" in header | `MilkdownEditor` (editable) |
| `centerMode: 'new'` | click `+` on folder or "New note" | `NewNoteForm` |

To change mode routing: `SplitLayout.jsx` center panel conditional render  
To toggle edit mode: `toggleMutable()` in store → flips `isMutable`

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

## Tab System

`TabBar.jsx` + `molecules/TabBar.module.css` — browser-style tab strip above the center header  
Tabs only render when `tabs.length > 0`. Each tab: title, dirty indicator (•), close button.

### Tab state shape

Each entry in `tabs[]`:
```js
{ path: string, pendingTitle: string, isMutable: boolean, hunks: Hunk[] }
```
`hunks` stores the diff from `currentNoteRaw` → `pendingRaw` for inactive tabs — used to restore unsaved edits when switching back.

### openTab(fullPath)

```
Already open? → switchTab(existingIndex)        (no fetch)
New tab:
  _snapshotTab()                                — save current tab's unsaved state
  openNote(fullPath)                            — fetch disk content, update store
  push { path, pendingTitle, isMutable:false, hunks:[] } → tabs[]
  set activeTabIndex = newTabs.length - 1
```
`openNote` is internal — called by `openTab`. Do not call `openNote` directly from UI.

### _snapshotTab()

Called before switching away from a tab. Computes `computeHunks(currentNoteRaw, pendingRaw)` and stores result in `tabs[activeTabIndex].hunks`. Also snapshots `pendingTitle` and `isMutable`.  
If no active tab, no-op.

### switchTab(index)

```
_snapshotTab()                                  — save current tab first
raw = fetchNoteContent(tab.path)                — always re-fetches from disk
if tab.hunks.length > 0:
  restoredPending = applyHunks(raw, tab.hunks)  — restore unsaved edits
else:
  restoredPending = raw
set store: currentNoteRaw, currentNotePath, pendingRaw=restoredPending,
           pendingFrontmatter, pendingTitle, isMutable, activeTabIndex
```
Re-fetch on every switch ensures we pick up any external changes to the file.  
`applyHunks` in `utils/diff.js` — if hunks are stale (file changed externally), falls back to disk version.

### closeTab(index)

```
Remove tab from tabs[]
If was active + more tabs remain:
  activate Math.min(index, newTabs.length - 1)
  switchTab(newActiveIndex)
If was active + no tabs remain:
  clear editor state entirely (currentNotePath = null, etc.)
If was not active:
  adjust activeTabIndex to account for removed entry
```
No disk write — closing a tab discards unsaved changes silently.

### Dirty indicator

`TabBar` shows `•` on a tab when `tab.hunks.length > 0`. Hunks are only written to the tab entry on `_snapshotTab` (called on switch away). While the tab is active, dirtiness is measured live as `computeHunks(currentNoteRaw, pendingRaw).length > 0` in `syncNote`, not from the tab's `hunks` field.

### Tab + cancel interaction

Clicking Cancel → `cancelEdit()` → resets `pendingRaw = currentNoteRaw`, `pendingTitle`, clears `tabs[activeTabIndex].hunks = []`, increments `editorResetKey`.  
`editorResetKey` is part of `MilkdownEditorInner`'s `key` prop — incrementing it forces Milkdown to remount with the clean `currentNoteRaw` body, discarding all ProseMirror state from the edit session.

To change cancel behavior: `useStore.js cancelEdit()`  
To change dirty indicator style: `molecules/TabBar.module.css` `.dirty`

---

## FolderTree / Tree loading

`FolderTree.jsx` — vault tree is lazily loaded folder-by-folder  
`fetchRootChildren()` on startup → `GET /api/children` (no param) → populates root  
Folder expand → `fetchChildrenOf(folderPath)` → `GET /api/children?folder=...` → merges into `tree`  
`noteIndex` — `Map<string, string>` (basename lowercased → full absolute path), rebuilt on each load and after create/rename

### FolderTree Search Filter

Local `query` state, passed to all `TreeNode` instances  
`SearchBar` rendered above new-note button; ⌘K / Ctrl+K globally focuses it (listener in `SearchBar.jsx`)  
`hasMatch(name, node, query)` — recursive: file = substring match on display name; folder = any descendant matches  
When query active: folders without matching descendants hidden; folders with matches force-open; non-matching files hidden  
"No results" empty state shown if all root entries filtered  
To add AI-note sparkle badge: pass `isAI={true}` to `NavItem` — shows trailing sparkle icon

---

## READ — open a note

`openTab(fullPath)` in `useStore.js`:
1. If path already in `tabs[]` → `switchTab(existingIndex)` (no fetch)
2. Otherwise: `_snapshotTab()` → `openNote(fullPath)` → append new tab → set `activeTabIndex`

`openNote(fullPath)` (internal):
1. `GET /api/text?noteName=<fullPath>` → raw markdown string
2. `splitFrontmatter(raw)` → `{ frontmatter, body }`
3. Store sets: `currentNoteRaw = raw`, `pendingRaw = raw`, `pendingFrontmatter = frontmatter`, `pendingTitle = basename`, `isMutable = false`, `centerMode = 'view'`
4. `MilkdownEditor` re-renders — `key={currentNotePath}-${editorResetKey}` forces a clean Milkdown remount

`splitFrontmatter` is in `utils/frontmatter.js` — strips the `---` block so body passed to Milkdown is clean markdown.

---

## WYSIWYG Editor (MilkdownEditor.jsx)

`MilkdownEditor` is the unified view + edit surface. `isMutable` controls whether ProseMirror is editable.

### Mount / remount

```
MilkdownEditor (outer)
  └─ FrontmatterTable           — read-only display of frontmatter fields
  └─ MilkdownProvider           — context provider
       └─ MilkdownEditorInner   — key={currentNotePath}-{editorResetKey} → remounts on note change OR cancel
            └─ Milkdown         — renders [data-milkdown-root] → .milkdown → .ProseMirror
```

### Milkdown configuration

```js
Editor.make()
  .config(ctx => {
    ctx.set(rootCtx, root);
    ctx.set(defaultValueCtx, body);          // body only — no frontmatter
    ctx.update(editorViewOptionsCtx, prev => ({ ...prev, editable: () => isMutable }));
    ctx.get(listenerCtx).markdownUpdated((_, md) => onBodyChange(cleanMilkdownOutput(md)));
  })
  .use(gfm)
  .use(history)
  .use(listener)
  .use(mathPlugin)            // before wikiLinkPlugin: \[...\] must be consumed first
  .use(obsidianImagePlugin)   // before wikiLinkPlugin: ![[]] must be consumed first
  .use(wikiLinkPlugin)
  .use(hashtagPlugin)         // after wikiLink so [[#heading]] is already consumed
  .use(livePreviewPlugin)
```

`markdownUpdated` fires on every keystroke → `cleanMilkdownOutput(md)` → `updatePending(body)` → `joinFrontmatter(pendingFrontmatter, body)` → stored as `pendingRaw`

### Toggling editable after mount

```js
useEffect(() => {
  if (loading) return;
  getInstance()?.action(ctx => {
    ctx.get(editorViewCtx)?.setProps({ editable: () => isMutable });
  });
}, [isMutable, loading]);
```

### Wiki-link click (view mode only)

`MilkdownEditor` outer div intercepts clicks via event delegation:  
`e.target.closest('[data-wiki-link]')` → looks up `noteIndex` → calls `openTab(fullPath)`  
When `isMutable` is true, clicks are passed through so ProseMirror can set cursor position.

To change wiki-link navigation: `MilkdownEditor.jsx handleClick`

---

## Custom Milkdown Plugins

### wikiLinkPlugin (`utils/wikiLinkPlugin.js`)

Handles `[[target]]` / `[[target|display]]`:

| Part | Role |
|---|---|
| `wikiLinkRemark$` | `$remark` — splits text nodes in mdast, inserts `wikiLink` nodes |
| `wikiLinkNode$` | `$node` — ProseMirror atom node; `toDOM` → `<span class="wiki-link" data-wiki-link="...">` |
| `wikiLinkInputRule$` | `$inputRule` — typing `[[...]]` creates the node inline |

`toMarkdown` uses `state.addNode('html', ...)` to emit `[[...]]` verbatim — avoids mdast-util-to-markdown escaping `[`.

### obsidianImagePlugin (`utils/obsidianImagePlugin.js`)

Handles `![[filename]]`:

| Part | Role |
|---|---|
| `obsidianImageRemark$` | `$remark` — splits text nodes, inserts `obsidianImage` nodes |
| `obsidianImageNode$` | `$node` — `toDOM` → `<img class="embedded-image" src="/api/images/...">` |

`toMarkdown` also uses `state.addNode('html', ...)` → `![[filename]]` verbatim.  
**Plugin order matters:** `obsidianImagePlugin` must come before `wikiLinkPlugin` in `.use()` chain.

### hashtagPlugin (`utils/hashtagPlugin.js`)

Handles `#tag` inline:  
`$remark` splits text nodes, inserts `hashtag` nodes.  
`$node` → `toDOM` → `<span class="md-tag">#tag</span>`.  
`toMarkdown` emits `#tag` verbatim.  
**Plugin order:** after `wikiLinkPlugin` so `[[#heading]]` anchors are already consumed.

### livePreviewPlugin (`utils/livePreviewPlugin.js`)

ProseMirror plugin (via `$prose`) — shows raw syntax markers when cursor is inside `em`, `strong`, or `code` marks.

How it works:
1. On every selection change: `markExtent(doc, cursorPos, markType)` finds the continuous range of a mark around the cursor
2. `Decoration.inline(..., { nodeName: 'span', class: 'pm-active-mark pm-${markName}', 'data-md-open': ..., 'data-md-close': ... })` wraps the range
3. CSS `::before`/`::after` on the span display the syntax chars; the mark's bold/italic/code styling is suppressed so the text looks like source

No DOM widget nodes — avoids the browser issue where non-editable widgets adjacent to the cursor swallow space key input.  
To change which marks get live preview: `utils/livePreviewPlugin.js` `MARK_SYNTAX`.

### mathPlugin (`utils/mathPlugin.js`)

Handles inline `$...$`, block `$$...$$`, and block `\[...\]` math:

| Part | Role |
|---|---|
| `mathRemark$` | `$remark` — splits text nodes for `$...$` inline; detects standalone `$$` and `\[` paragraph nodes |
| `mathInlineNode$` | `$node` — atom; `toDOM` renders `katex.renderToString(value, { displayMode:false })` into `<span class="math-inline">` |
| `mathBlockNode$` | `$node` — atom; `toDOM` renders `katex.renderToString(value, { displayMode:true })` into `<div class="math-block">` |

`toMarkdown` preserves original syntax — `$$` → `$$\n...\n$$`, `\[` → `\[\n...\n\]`.  
Math nodes are `contenteditable="false"` — read-only atoms in the editor.  
KaTeX CSS loaded globally in `main.jsx`.  
**Plugin order:** before `wikiLinkPlugin` — `\[...\]` must be consumed before `[[` is scanned.

### cleanMilkdownOutput (`utils/markdownCleanup.js`)

Post-processes Milkdown's serialized output before storing:
1. Strips lines that are only `<br />` (Milkdown's empty-paragraph markers — Obsidian doesn't need them)
2. Un-escapes `\#tag` at line start when not followed by space (`#hashtag` is valid Obsidian syntax, not a heading)

Math blocks and wiki-links are emitted as raw HTML by their `toMarkdown` handlers — `cleanMilkdownOutput` does not touch them.

---

## CREATE

`+` button on folder hover → `startNewNote(folderPath)` → `centerMode = 'new'`, `newNoteName = ''`  
"New note" at top of FolderTree → `startNewNote(vaultRoot)` (creates at vault root)  
Center header becomes an `<input>` — user types name there  
Enter in header input or "Create" button in `NewNoteForm` → `createNote(folder, newNoteName)`  
→ `POST /api/notes` → `{ path: absolutePath }` → `openTab(path)` → view mode

`newNoteName` in Zustand store, set via `setNewNoteName()`, reset on `startNewNote`/`cancelNewNote`  
`NewNoteForm` is folder hint + Create/Cancel — no name input of its own  
Backend: creates `folder/name.md` with frontmatter `sr-due: today+3d`, `sr-interval: 3`, `sr-ease: 200`  
To change initial frontmatter: `FileRepository.createNote()`

---

## UPDATE (WYSIWYG save)

"Edit" button in header → `toggleMutable()` → `isMutable = true` → Milkdown becomes editable  
User edits content and/or title → header shows editable title input (`pendingTitle`)

**Save** → `syncNote()` in store:
1. If `pendingTitle !== currentTitle` → `PATCH /api/notes/rename` → get `newPath`
2. `computeHunks(currentNoteRaw, pendingRaw)` [utils/diff.js] — LCS diff, CRLF-normalized
3. If hunks non-empty → `PATCH /api/notes/content` with `{ path, hunks }`
4. If hunks empty → skip the network call
5. `currentNoteRaw = pendingRaw`, `isMutable = false`
6. Update active tab: `path = savePath`, `hunks = []`, `isMutable = false`
7. Refresh: `fetchChildrenOf(parentFolder)` + `fetchNoteNames()` + `fetchReviewNotes()`

**Cancel** → `cancelEdit()` in store:
1. `pendingRaw = currentNoteRaw` — discard all edits
2. `pendingTitle = noteBasename(currentNotePath)` — discard title change
3. `isMutable = false`
4. `tabs[activeTabIndex].hunks = []` — clear dirty indicator
5. `editorResetKey += 1` — forces Milkdown to remount with clean body (no disk fetch needed)

Diff: `utils/diff.js lcsBacktrack()` — O(m×n) LCS → edit ops → compressed hunks  
Hunk shape: `{ startLine: number, deleteCount: number, insertLines: string[] }`  
CRLF handling: both sides normalized with `.replace(/\r\n/g, '\n')`  
Backend applies hunks back-to-front, preserves original line separator  
To change diff algorithm: `utils/diff.js lcsBacktrack()`  
To revert to full-replace: call `updateNote(path, content)` (`PUT /api/notes`) instead of `patchNote` in `syncNote()`

Cross-file `[[link]]` reference updating on rename is handled server-side via `NoteLinkRepository`.

---

## DELETE (soft)

Two entry points, both call `deleteNote(path)` → `DELETE /api/notes` → backend moves file to `ROOT/_trash/`

| Where | How to reach |
|---|---|
| Left panel tree | Hover any file → 🗑 icon appears → `window.confirm` → delete |
| Center header | Open note in view mode → 🗑 button next to "Edit" → `window.confirm` → delete |

After delete: remove tab from `tabs[]`; if it was active, activate nearest remaining tab or clear editor.  
Tree + review re-fetched.  
Recovery: manual file move from `_trash/` back to vault [NOT IMPLEMENTED in UI]

---

## Frontmatter — display and round-trip

`splitFrontmatter(raw)` (`utils/frontmatter.js`) extracts the `---` block including trailing blank lines.  
`parseFrontmatterFields(frontmatter)` → `[{key, value}]` for display in `FrontmatterTable`.  
`FrontmatterTable` renders above the Milkdown surface — read-only (no inline frontmatter editing).  
`joinFrontmatter(frontmatter, body)` re-attaches on every `updatePending` call.  
Frontmatter is never passed into Milkdown — Milkdown only receives and serializes the body.

To edit frontmatter: currently not supported in UI — user must use raw text. [NOT IMPLEMENTED]

---

## API Layer (api/notes.js)

All calls prefixed by `ENV.API_BASE` (default `/api`)  
`ApiError` class carries `.status` — store actions check `e.status === 401` to show login modal  
Write calls use `credentials: 'same-origin'` — session cookie included automatically

| Function | Method | Auth required |
|---|---|---|
| `fetchNames()` | `GET /api/names` | Yes |
| `fetchChildren(folder?)` | `GET /api/children[?folder=]` | Yes |
| `fetchReview(offset, limit)` | `GET /api/review?offset=&limit=` | Yes |
| `fetchNoteContent(path)` | `GET /api/text?noteName=` | Yes |
| `checkAuth()` | `GET /api/me` | — |
| `login(u, p)` | `POST /api/login` | No |
| `logout()` | `POST /api/logout` | No |
| `createNote(folder, name)` | `POST /api/notes` | Yes |
| `updateNote(path, content)` | `PUT /api/notes` | Yes (unused — full-replace fallback) |
| `patchNote(path, hunks)` | `PATCH /api/notes/content` | Yes |
| `renameNote(oldPath, name)` | `PATCH /api/notes/rename` | Yes |
| `deleteNote(path)` | `DELETE /api/notes` | Yes |

---

## Write Roundtrip — Obsidian Compatibility

**Critical invariant:** the app reads and writes raw markdown. Milkdown receives only the body (no frontmatter) and its serialized output is cleaned before storing.

```
Backend disk  →  GET /api/text  →  currentNoteRaw (raw .md, unchanged)
                                 ↓
                         splitFrontmatter()
                           ↓              ↓
                      frontmatter       body
                           ↓              ↓
                    FrontmatterTable  MilkdownEditor (body only)
                                           ↓ (on keystroke)
                               cleanMilkdownOutput(serialized body)
                                           ↓
                               updatePending(body)
                                           ↓
                               joinFrontmatter(fm, body) → pendingRaw
                                           ↓ (on Save)
                               computeHunks(currentNoteRaw, pendingRaw)
                                           ↓
                               PATCH /api/notes/content → disk
```

`[[links]]`, `![[images]]`, `#hashtags`, `$math$`, `$$math$$`, `\[math\]`, frontmatter — all preserved verbatim.  
Custom plugins serialize back to raw Obsidian/LaTeX syntax via `toMarkdown`.  
`cleanMilkdownOutput` strips `<br />` markers and un-escapes `\#tags`.  
To change the editor: `MilkdownEditor.jsx`. Do NOT save `currentNoteHtml` or Milkdown's HTML to disk.

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
| Edit mode enter | `useStore.js toggleMutable()` |
| Edit mode cancel (discard) | `useStore.js cancelEdit()` |
| WYSIWYG save flow | `useStore.js syncNote()` |
| Tab open / switch / close | `useStore.js openTab() / switchTab() / closeTab()` |
| Tab dirty indicator style | `molecules/TabBar.module.css` `.dirty` |
| Milkdown plugins | `utils/wikiLinkPlugin.js`, `utils/obsidianImagePlugin.js`, `utils/hashtagPlugin.js` |
| Math rendering / syntax | `utils/mathPlugin.js` |
| Live preview syntax chars | `utils/livePreviewPlugin.js` `MARK_SYNTAX` |
| Milkdown output cleanup | `utils/markdownCleanup.js cleanMilkdownOutput()` |
| Frontmatter split/join | `utils/frontmatter.js` |
| Frontmatter display | `molecules/FrontmatterTable.jsx` |
| Editor CSS / height chain | `organisms/MilkdownEditor.module.css` |
| Wiki-link navigation | `organisms/MilkdownEditor.jsx handleClick` |
| New note form | `organisms/NewNoteForm.jsx` / `NewNoteForm.module.css` |
| Diff algorithm | `utils/diff.js lcsBacktrack()` |
| Login modal UI | `organisms/LoginModal.jsx` / `LoginModal.module.css` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |

---

## Residual (next session)

- **Frontmatter editing** — inline editing of frontmatter fields in the UI [NOT IMPLEMENTED]
- **Trash UI** — list and restore notes from `_trash/`
- **Review page** — build out `ReviewPage.jsx` (currently a stub)
- **Settings page** — build out `SettingsPage.jsx` (currently a stub)
- **Math editing** — math nodes are currently read-only atoms; clicking to edit inline is not implemented
- **Tab persistence** — tab state is lost on page refresh (Zustand in-memory only)
