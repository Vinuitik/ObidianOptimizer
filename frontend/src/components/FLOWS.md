# Components Flows

Files: atoms/Button.jsx, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, atoms/Toast.jsx, molecules/SearchBar.jsx, molecules/FrontmatterTable.jsx, molecules/PanelHeader.jsx, molecules/NavItem.jsx, molecules/ReviewRating.jsx, molecules/TabBar.jsx, organisms/FolderTree.jsx, organisms/MilkdownEditor.jsx, organisms/NoteEditor.jsx, organisms/NoteViewer.jsx, organisms/NewNoteForm.jsx, organisms/ReviewList.jsx, organisms/NavBar.jsx, organisms/LoginModal.jsx, organisms/SyncBanner.jsx, organisms/EditorErrorBoundary.jsx, templates/SplitLayout.jsx

---

## MilkdownEditor

`MilkdownEditor` (outer) → reads store → renders:
```
FrontmatterTable           — read-only frontmatter display
MilkdownProvider           — key={currentNotePath}-{editorResetKey} → full context teardown on note change OR cancel
  MilkdownEditorInner      — stateless; remounts with provider
    Milkdown               — [data-milkdown-root] → .milkdown → .ProseMirror
```

**Key must be on `MilkdownProvider`** (not `MilkdownEditorInner`) — keying only the inner lets the schema context persist, causing `doc` node collision on second init.

### Editor Plugin Chain

```js
Editor.make()
  .config(ctx → { rootCtx, defaultValueCtx (body only), editorViewOptionsCtx, listenerCtx.markdownUpdated })
  .use(commonmark)        // REQUIRED: doc + all base ProseMirror nodes
  .use(gfm)              // REQUIRED alongside commonmark: tables/strikethrough/tasks
  .use(history)
  .use(listener)
  .use(prism)            // syntax highlighting for fenced code blocks
  .use(mathPlugin)       // before wikiLinkPlugin: \[...\] consumed first
  .use(obsidianImagePlugin) // before wikiLinkPlugin: ![[]] consumed first
  .use(wikiLinkPlugin)
  .use(hashtagPlugin)    // after wikiLink: [[#heading]] already consumed
  .use(livePreviewPlugin)
```

`gfm` alone → `Schema is missing top node type ('doc')` — both presets required.

`markdownUpdated` → `cleanMilkdownOutput(md)` → `updatePending(body)` → `joinFrontmatter(fm, body)` → `pendingRaw`

### Toggle Editable After Mount

```js
useEffect(() => {
  getInstance()?.action(ctx => ctx.get(editorViewCtx)?.setProps({ editable: () => isMutable }));
}, [isMutable, loading]);
```

### Wiki-link Click (view mode)

`MilkdownEditor` outer `onClick` → `e.target.closest('[data-wiki-link]')` → `noteIndex` lookup → `openTab(fullPath)`  
`isMutable` = true: clicks pass through to ProseMirror.

### File Attach — Paste, Drag-and-Drop, File Picker

All three paths call the same `insertFiles(files)` function inside `MilkdownEditorInner`:

```
insertFiles(files)
  → filter by isWhitelisted()
  → generateFilename(file) + createObjectURL → addPendingBlob (module registry)
  → onFilePaste(filename, file, blobURL)  — persists in store for tab switching
  → getInstance().action → obsidianImageNode$.create({ filename }) → replaceSelectionWith
```

**Paste**: `view.dom.addEventListener('paste')` → reads `e.clipboardData.files`  
**Drag-drop**: `dragover` → `preventDefault` if files present + `setIsDragOver(true)`; `dragleave` → clears only when leaving editor; `drop` → reads `e.dataTransfer.files`  
**File picker button**: hidden `<input type="file" multiple accept={ACCEPT}>` triggered by `📎` button (edit mode only, bottom-right of editor). `ACCEPT` built from `WHITELISTED_EXTS`.

Drag-over shows `.dragOverlay` — dashed accent border + semi-transparent bg (`pointer-events: none`).  
To change accepted file types: `obsidianImagePlugin.js *_EXTS + WHITELISTED_MIME_TYPES`

### Wiki-link Suggest (`[[` autocomplete)

Triggered when the cursor is inside `[[query` text in the editor:

```
input / selectionchange event on view.dom
  → window.getSelection().focusNode.textContent.slice(0, offset)
  → match /\[\[([^\]]{0,80})$/
  → setWikiQuery(match[1]), setWikiRect(range.getBoundingClientRect())
  → WikiLinkSuggest portal: useSearch(query, minLength=1) → results dropdown
  → onMouseDown on result → e.preventDefault() (keeps editor focused)
      → insertWikiLink(displayName)
          → getInstance().action → state.doc.textBetween(blockFrom, cursor)
          → lastIndexOf('[[') → replaceWith(wikiLinkNode$.create({ target: displayName }))
```

Escape closes without inserting. `onMouseDown` + `preventDefault` is critical — prevents blur before click registers.

To change trigger minimum length: `WikiLinkSuggest` → `useSearch(query, minLength)`  
To change result count: `searchNotes` limit param

---

## FolderTree

Vault tree lazily loaded by folder. `fetchRootChildren()` on startup → `GET /api/children`.  
Folder expand → `fetchChildrenOf(path)` → `GET /api/children?folder=...`.  
`noteIndex` — `Map<basename.lower, fullPath>`, rebuilt after create/rename.

Folder rows carry three hover actions via `NavItem`: new-subfolder (`onAddFolder`),
new-note (`onAdd`), and **delete → trash** (`onDelete`, 🗑) — the last confirms then calls
`store.deleteFolder(fullPath)` → `DELETE /folders` (recursive soft-delete). File rows keep
only 🗑 (`deleteNote`).

### Drag-and-Drop Move

`DRAG_TYPE = 'application/obsidian-note'` — namespaced, rejects unrelated browser drags.

**File nodes** — `onDragStart` → `setData(DRAG_TYPE, node.fullPath)` + `effectAllowed='move'`

**Folder nodes**:
```
onDragOver  → preventDefault + dropEffect='move' + setDragOver(true)
onDragLeave → setDragOver(false) only when leaving folder subtree
              (guards: e.currentTarget.contains(e.relatedTarget))
onDrop      → getData(DRAG_TYPE) → guard source !== target → moveNote()
```

`isDragOver` → `NavItem .dragOver` class (accent-dim background + accent-border outline).

To change drop highlight: `NavItem.module.css .dragOver`  
To change accepted drag types: `FolderTree.jsx DRAG_TYPE`

### Search — Local Filter + Semantic Dropdown

Local `query` state filters the tree (substring match on display names).  
`SearchBar` above new-note button; `⌘K` / `Ctrl+K` globally focuses it.

**Semantic search dropdown** (from `SearchBar.jsx`):
```
onFocus → setFocused(true)
value changes → useSearch(focused ? value : null) → 500ms debounce → GET /search?q=
results → createPortal(<dropdown>) positioned via containerRef.getBoundingClientRect()
onMouseDown on result → e.preventDefault() → openTab(notePath) → setFocused(false)
onBlur → setTimeout(150ms) → setFocused(false)  ← delay allows mousedown to fire first
```

`useSearch` hook: AbortController kill-switch — each new query aborts the in-flight request before debounce timer fires. See `utils/FLOWS.md`.

`hasMatch(name, node, query)` — file: substring match; folder: any descendant matches.  
Active query: folders without matching descendants hidden; folders with matches force-open.

**Hook hoisting note**: `isForceOpen` `useEffect` must be declared before any conditional early return — React hook-count invariant.

---

## TabBar

`tabs.length > 0` → render tab strip.  
Each tab: title, `•` dirty indicator when `tab.hunks.length > 0`, close button.

`•` while tab is active: live dirtiness measured as `computeHunks(currentNoteRaw, pendingRaw).length > 0` in `syncNote`, not from tab's `hunks` field.

Tab `<button>` contains `<span role="button" aria-label="Close tab">` — `getAllByRole('button')` returns both; filter on `aria-label` in tests.

To change dirty style: `molecules/TabBar.module.css .dirty`

---

## SplitLayout

Left and right panels resizable via 4px `.resizeHandle`.  
`useResize(initialWidth)` hook: `mousedown` → global `mousemove/mouseup` → width state.  
Constants: `MIN_PANEL_WIDTH=160`, `MAX_PANEL_WIDTH=480`, `DEFAULT_WIDTH=290`.

Center panel routing (from store `centerMode`):
- `centerMode: 'view'` → `MilkdownEditor` (read-only or editable per `isMutable`)
- `centerMode: 'new'` → `NewNoteForm`

To change default panel width: `SplitLayout.jsx DEFAULT_WIDTH`  
To change center panel routing: `SplitLayout.jsx` center panel conditional

---

## NavBar

Fixed top bar, 56px height on desktop.  
Auth state from `isAuthenticated` → "Sign in" / "Sign out".  
`NavLink` from react-router-dom — `.linkActive` applied on active route.

Mobile (≤768px): CSS-only two-row wrap — brand + auth on row 1 (auth always on-screen), links on row 2, horizontally scrollable, ≥44px touch targets. See `NavBar.module.css` `@media` block.

Streak display: hardcoded "12-day streak" — wire to `streakDays` in store when backend supports it.

---

## LoginModal

Renders over app when `showLogin = true`.  
`<label htmlFor="login-username">` / `<label htmlFor="login-password">` — required for `getByLabelText` in tests.  
Submit → `POST /api/login` → `isAuthenticated = true`, `showLogin = false`.  
Overlay click / Cancel → `showLogin = false`.

Overlay is `overflow-y: auto` + modal `margin: auto` — on-screen keyboard shrinking the viewport scrolls instead of clipping the modal top.

Full site: shown on-demand (401). Installed PWA: auto-opened when `checkAuth()` returns
unauthenticated while online — see `pwa/FLOWS.md` "Auto sign-in prompt" (`MobileLayout` gate).

---

## SyncBanner

`organisms/SyncBanner.jsx` — global slim bar in the full-site shell (`App.jsx`, above the
routes). Polls the CHEAP `GET /sync/progress` (no Drive quota call) — every 3s while a
Drive pull / DB restore is active, a 15s heartbeat when idle; only when `isAuthenticated`.
Renders nothing unless `downloading` or `dbRestoring`. Message tells the user the vault
isn't 100% local yet ("Some content may not be available yet" / restore phase), so a
missing note reads as "still syncing" not a bug. Failed-file count (`downloadFailed`)
appears inline as "N to retry". To change cadence/copy: `SyncBanner.jsx`.

---

## Responsive / Mobile (site-wide)

Single breakpoint: `@media (max-width: 768px)` everywhere (documented in `styles/tokens.css`; matches `pwa/ResponsiveApp`). All CSS-only except two JS touches marked below.

- `App.module.css .shell` — `100dvh` (visible viewport; `100vh` fallback) + `safe-area-inset-bottom`
- `NavBar.module.css` — two-row wrap (see NavBar above); `safe-area-inset-top`
- `SplitLayout` — side panels become absolute overlay **drawers** (`z-index: 50`, `min(85vw, 320px)`), not `display:none`; collapsed = hidden. **JS touches:** `useStore.js` inits `leftCollapsed`/`rightCollapsed` via `matchMedia` so drawers start closed on phones; `SplitLayout.jsx` renders a **tap-to-dismiss `.scrim`** (`useIsMobile`, `z-index: 40`) behind an open drawer → `closeDrawers()`. Open via header "▶ Files"/"Review ◀".
- `LearnLayout` — **display logic changes on mobile, not just CSS:** `LearnLayout.jsx` branches on `useIsMobile` → renders ONE pane at a time with a segmented **[labelA | labelB]** toggle (`.segmented`) instead of the desktop drag-split. Slots unchanged; `InboxReview` passes `labelA="Source" labelB="Note"`. Desktop split path (and its tests) untouched.
- `InboxReview` — mobile stacks: queue rail → full-width top strip (`max-height: 32vh`), split area single-view, action bar `position: sticky; bottom`.
- `ReviewPage.module.css` — one pane at a time: list full-screen ↔ session full-screen. **JS touch:** `ReviewPage.jsx` sets `.hasSession` class when a session/inline note is open
- `ResourcePanel/InboxPanel .module.css` — fixed sidebars → `min(40-45vw, …)`
- `DashboardPage/SettingsPage .module.css` — tighter gutters

Drawer backdrop (tap-to-close) DONE (`SplitLayout.jsx .scrim`). [NOT IMPLEMENTED]: swipe-to-close; live re-collapse when resizing desktop→mobile (initial-load only).

---

## Toast

`atoms/Toast.jsx` — fixed bottom, auto-dismisses after 4 s.  
Controlled by `store.toastMessage` + `showToast()`.

---

## Change Index

| Thing to change | Where |
|---|---|
| Milkdown plugin chain | `organisms/MilkdownEditor.jsx useEditor` |
| Wiki-link navigation | `organisms/MilkdownEditor.jsx handleClick` |
| Editor CSS / height | `organisms/MilkdownEditor.module.css` |
| File attach accepted types | `obsidianImagePlugin.js *_EXTS + WHITELISTED_MIME_TYPES` |
| Drag-over overlay style | `organisms/MilkdownEditor.module.css .dragOverlay` |
| File attach button style | `organisms/MilkdownEditor.module.css .uploadBtn` |
| Wiki-link suggest min length | `WikiLinkSuggest` → `useSearch(query, minLength)` |
| Semantic search dropdown style | `molecules/SearchBar.module.css .dropdown*` |
| FolderTree drag type | `organisms/FolderTree.jsx DRAG_TYPE` |
| Drop highlight style | `molecules/NavItem.module.css .dragOver` |
| Search filter logic | `organisms/FolderTree.jsx hasMatch()` |
| Tab dirty indicator style | `molecules/TabBar.module.css .dirty` |
| Resizable panel defaults | `templates/SplitLayout.jsx MIN/MAX/DEFAULT_WIDTH` |
| Mobile breakpoint (768px) | every `@media` block; convention doc in `styles/tokens.css` |
| Mobile drawer width/behavior | `templates/SplitLayout.module.css` `@media` block |
| Drawer initial collapsed state | `store/useStore.js leftCollapsed/rightCollapsed` |
| Sync banner copy / poll cadence | `organisms/SyncBanner.jsx` (`fetchSyncProgress`, 3s/15s) |
| PWA auto sign-in prompt | `pwa/MobileLayout.jsx` `gate()` effect (`setShowLogin`) |
| App-level crash boundary (PWA) | `organisms/RouteErrorBoundary.jsx` wraps `<Outlet>` in `pwa/MobileLayout.jsx` — catches leaf throws so a bad screen (e.g. Learn) can't blank the app / kill the nav; resets per route (`key=pathname`) |
| Review mobile pane switch | `pages/ReviewPage.jsx hasSession` + `.module.css` `@media` |
| Center panel routing | `templates/SplitLayout.jsx` center conditional |
| Streak display | `organisms/NavBar.jsx` hardcoded string |
| Toast dismiss timing | `atoms/Toast.jsx` + `store/useStore.js showToast()` |
| New note form | `organisms/NewNoteForm.jsx` |
| Frontmatter display | `molecules/FrontmatterTable.jsx` |
| Ingest-review source panel | `organisms/SourceSplicePanel.jsx` — kind (`sourceKind`) → viewer: youtube iframe / `ClipVideo` (local) / `PdfPages` (local pdf) / web iframe / RSVP·Read (text). Region from `utils/inboxParse.parseSourceRegion` |
| PDF source: page render + region toggle | `SourceSplicePanel.PdfPages` → `<img src=/pdf-page?path=&page=[&box=]>`; "Show region" toggle adds `box=` (drawn server-side). `vaultPdfPath()` gates local-vs-external. v1 notes = clean page (no bbox) |
| Video clip start-seek | `SourceSplicePanel.ClipVideo` — `currentTime=start` on `loadedmetadata` (NOT `#t=`, browsers ignore start); `onTime` pauses once at `end` |
| Media URL encoding | `SourceSplicePanel.mediaUrl()` — per-segment `encodeURIComponent` (spaces/fullwidth-colon in yt-dlp names) |
| Inbox order within a source | `utils/sourceColor.groupBySource()` → `sourcePosition()` (min page / clip start), NOT captureSeq — reading order |
| Source footer parse (pages/clip/bbox) | `utils/inboxParse.parseSourceRegion()` (`pages`, `startSeconds`/`endSeconds`, `local`, `bboxes`) |
