# Components Flows

Files: atoms/Button.jsx, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, atoms/Toast.jsx, atoms/RefreshButton.jsx, molecules/SearchBar.jsx, molecules/FrontmatterTable.jsx, molecules/PanelHeader.jsx, molecules/NavItem.jsx, molecules/ReviewRating.jsx, molecules/TabBar.jsx, organisms/FolderTree.jsx, ../editor/CmEditor.jsx (see editor/FLOWS.md), molecules/MarkdownView.jsx, organisms/NoteEditor.jsx, organisms/NoteViewer.jsx, organisms/ReviewList.jsx, organisms/NavBar.jsx, organisms/LoginModal.jsx, organisms/SyncBanner.jsx, organisms/QuitGuard.jsx, organisms/EditorErrorBoundary.jsx, templates/SplitLayout.jsx, organisms/FeedView.jsx, organisms/FeedSources.jsx

---

## Note editor (CM6) and read-only renderer

The main note surface is CodeMirror 6: `frontend/src/editor/CmEditor.jsx` — all flows (load, debounced save path,
live preview, `[[` suggest, attachments, math/tables, CRLF) live in **`frontend/src/editor/FLOWS.md`**.
Read-only views (Review, Inbox, SourceSplicePanel, PWA notes) use `molecules/MarkdownView.jsx`
(markdown-it via `utils/markdown.js`, NOT contentEditable) — see `utils/FLOWS.md`.

To change the editor: `editor/FLOWS.md` Change Index. To change read-only rendering: `utils/markdown.js`.

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
- `isMutable` → `editor/CmEditor` (live preview); not mutable + note open → `.reading` div with `MarkdownView` of `pendingRaw`
- No new-note screen: the `+` actions call `store.startNewNote()` (instant create, see store/FLOWS.md)

To change default panel width: `SplitLayout.jsx DEFAULT_WIDTH`  
To change center panel routing: `SplitLayout.jsx` center panel conditional

### Edit toggle, autosave status, title
Notes open in edit mode. ONE toggle button (`Reading view` ↔ `Edit`) + window Ctrl/Cmd+E → `store.toggleMutable()` (saves on leaving edit). No Save/Cancel/discard; Ctrl+Z is the undo.
Window listeners (while a note is open): Ctrl/Cmd+S, `blur`, `beforeunload`, `visibilitychange`(hidden) → `store.flushSave()`. Idle autosave lives in the store (1500 ms).
Status (`saveLabel`): "Saving…" (saving or dirty) / "Saved" / button "Save failed — retry" (`saveStatus==='error' && dirty`, click = `flushSave`). Toast on failure comes from `store.flushSave`.
Title (edit mode) is a single-line `<input>` with local `titleDraft`; Enter/blur → `store.commitTitle()`, Esc reverts. `titleFocusNonce` change → input `.focus()+.select()` (new note).
No note open → header title blank; the single "Select a note" message is `CmEditor`'s empty pane. Mobile: collapsed-Files opener is a full-width 44px row (`.filesBtn`).
To change: `SplitLayout.jsx` (listeners effect, `saveLabel`, `submitTitle`); look: `SplitLayout.module.css .headerTitleInput / .saveState / .reading`

Technology Notes: autosave is store-level and in-memory; a hard crash/`beforeunload` mid-request can lose the last <=1.5 s (beforeunload save is best effort, async fetch may be cancelled). Title input height bug (fixed): the old `<textarea>` inherited `base.css :where(textarea){min-height:80px}` -> use `min-height:0` on any compact field.

---

## NavBar

Desktop top bar: Review · Sources · Learn · Notes (`useNavItems` items with `primary`), then VaultSwitcher, QuickCapture, avatar menu (Settings, Refresh, Sign out). Signed out → "Sign in" button.
Dashboard / Failures / Get App are NOT in the bar: links live in Settings → System (`SettingsPage.SystemGroup`); failures count shows only there. To change nav items: `useNavItems.NAV_ITEMS`; avatar menu: `NavBar.jsx`.

Phone (≤768px): brand + RefreshButton row; avatar menu hidden; `MobileTabBar` "More" sheet lists non-primary items (Settings) + Sign in/out.

`QuickCapture` (molecule, authed only) sits left of RefreshButton - see next section.

Streak display: hardcoded "12-day streak" — wire to `streakDays` in store when backend supports it.

---

## QuickCapture (molecule, in NavBar)

Files: QuickCapture.jsx, QuickCapture.module.css
"+ Capture" button or `c` key (not while typing in input/textarea/contenteditable; Esc/outside click closes) → popover → `classifyInput()` (from `TrackAddBox`):
- urls → `api/capture.captureResource(url)` per line (no track)
- file (Attach / drop) → `api/capture.captureFile(file)`
- text → `api/notes.createNote(<vaultRoot>/_inbox, noteNameFromText())` + `updateNote(path, text)`

Success → `showToast({tone:'success', action:'View in Inbox' → navigate('/learn')})`; all-failed → inline error, popover stays open.
Technology note: text notes assume `<vaultRoot>/_inbox` exists and that createNote accepts it as `folder`; the Inbox tab lives on `/learn`, so if Learn stops defaulting to Inbox the toast link lands on Feed.

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

## RefreshButton

`atoms/RefreshButton.jsx` — see `pwa/FLOWS.md` "update detection + manual refresh" for
the full flow (SW update signal, `registerSW.js`, desktop shell). Mounted in
`organisms/NavBar.jsx` and `pwa/MobileLayout.jsx`.

## FeedView / FeedSources (organisms)
`FeedView({view,onView})` renders ONLY the body (tab row Feed · Subscriptions lives in `SourcesPage`). `Stack` mounts → `fetchFeed()`; `feedError` → `ErrorState`+Retry, `!feedLoaded` → skeleton, empty → EmptyState "Nothing new" + "Manage subscriptions" (`onView('subscriptions')`). `fetchFeed` has a 15s timeout (`FEED_FETCH_TIMEOUT_MS`) so loading always resolves. Actions: Bin secondary, Accept primary, Undo ghost. (No Bin tab — see Settings ▸ Bin). `Stack`: `AnimatePresence` + `motion/presets.swipe` — exit flies out in the decision direction (`feedLastDecision.kind`), next card scales in. `FeedCard` pointer events → dx → transform on the inner div; release past `SWIPE_PX` (80) → accept/bin. Keys ←/→ (ignored in INPUT/TEXTAREA/SELECT). Undo button in the action bar (`undoFeed`); toasts: accept "Sent to Inbox · View", bin "Binned. · Undo".
Loading: `!item && !feedLoaded` → Skeleton card (never "all caught up" before the first fetch); empty → `EmptyState` + "Open Inbox".
`FeedSources` subscribe = URL → **Preview** (`resolveFeedSource`, nothing persisted; Card with human kind label + 3 items) → **Subscribe** (`addFeedSource`) → `onSubscribed()` → SourcesPage switches to Feed. Delete → store `deleteFeedSource` (optimistic, Undo toast). Kind labels: `FeedSources.kindLabel`.
`LearnPage` no longer mounts FeedView; before W2 NOTHING called `fetchFeed` (skeleton forever) — now `Stack` does. store `learnView` is only set by the Feed toast "View"/"Open Inbox" and is currently unused by LearnPage.
To change threshold: `FeedView.jsx SWIPE_PX`. Auto-accept tri-state: `FeedSources.jsx toVal/fromVal`.
Offline: `decideFeed`/`restoreFeedItem` route through `pwa/offlineApi.js`; Sources stay online-only.
`FeedCard` "Subscribe to this channel" (youtube.com/youtu.be items) → `subscribeToChannel(url)` → `addFeedSource` (direct, no preview).
## InboxReview (organism)
`load({preferGroup})` after file/discard: next note in the same group, else the first note of the next group (no dead stop). Enter = Save & file (Save & acknowledge for in-place): window listener, skipped while typing / on focused non-row buttons / picker open; Ctrl/Cmd+Enter works inside the editor. Preview strips a leading `# Title` equal to the note title (`stripLeadingTitle`, display only). Queue row: folder chip shrinks first (`.rowTag`), title `min-width: 12ch`. `loading` starts true (Skeleton, not the empty message). `SourceSplicePanel` text sources default to Read, RSVP secondary.
Tests: `__tests__/InboxFlow.test.jsx`, `FeedFlow.test.jsx`, `LearnPage.test.jsx`.
Tests: `__tests__/FeedView.test.jsx` (API mocked; jsdom lacks PointerEvent so events are synthesized).

---

## Change Index

| Thing to change | Where |
|---|---|
| Note editor internals | `editor/FLOWS.md` Change Index |
| Read-only markdown rendering | `utils/markdown.js` |
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
| "Are you a quitter?" guard | `organisms/QuitGuard.jsx` (mounted in `App.jsx`) — arms native `beforeunload` prompt + shows quote modal on tab-refocus while duty unfinished. Duty = `utils/dailyDuty.dutyUnfinished()` (`learnTaskDoneToday()` localStorage day-stamp AND `fetchReview(0,1)` due-probe). Nag copy/quotes: `utils/quotes.js`. Learn-done stamp set in `organisms/InboxReview.jsx` file()/acknowledge() via `markLearnTaskDone()`. **Web can't render custom UI at true close (browser limit) → the on-close modal is a desktop-app (Tauri/Electron) capability** |
| PWA auto sign-in prompt | `pwa/MobileLayout.jsx` `gate()` effect (`setShowLogin`) |
| App-level crash boundary (PWA) | `organisms/RouteErrorBoundary.jsx` wraps `<Outlet>` in `pwa/MobileLayout.jsx` — catches leaf throws so a bad screen (e.g. Learn) can't blank the app / kill the nav; resets per route (`key=pathname`) |
| Review mobile pane switch | `pages/ReviewPage.jsx hasSession` + `.module.css` `@media` |
| Center panel routing / reading view | `templates/SplitLayout.jsx` center conditional |
| Streak display | `organisms/NavBar.jsx` hardcoded string |
| Toast dismiss timing | `atoms/Toast.jsx` + `store/useStore.js showToast()` |
| New note (instant) | `store.startNewNote()`; `+` in `organisms/FolderTree.jsx` |
| Frontmatter display | `molecules/FrontmatterTable.jsx` |
| Ingest-review source panel | `organisms/SourceSplicePanel.jsx` — kind (`sourceKind`) → viewer: youtube iframe / `ClipVideo` (local) / `PdfPages` (local pdf) / web iframe / RSVP·Read (text). Region from `utils/inboxParse.parseSourceRegion` |
| PDF source: page render + crop toggle | `SourceSplicePanel.PdfPages` → `<img src=/pdf-page?path=&page=[&box=][&crop=1]>`. **Default now CROPS to the sourced region** (`crop=1`, needs bbox) so a mid-page chapter isn't drowned in the rest of the page. "Crop to region" checkbox (`cropRegion`, on by default) → unchecked shows the full page + highlight box. "⤢ Full PDF" (`fullDoc`) = scrollable whole-doc `<embed>` escape hatch. Server crop = `get_pixmap(clip=…)` in `embedder/main.py pdf_page()` (`crop` param). `pdfPageUrl(path,page,box,crop)` in `utils/noteMedia.js` — shared with the offline warm (warms clean+crop+highlight variants). `vaultPdfPath()` gates local-vs-external; v1 notes = clean page (no bbox) |
| Video clip start-seek | `SourceSplicePanel.ClipVideo` — `currentTime=start` on `loadedmetadata` (NOT `#t=`, browsers ignore start); `onTime` pauses once at `end` |
| Media URL encoding | `SourceSplicePanel.mediaUrl()` — per-segment `encodeURIComponent` (spaces/fullwidth-colon in yt-dlp names) |
| Inbox order within a source | `utils/sourceColor.groupBySource()` → `sourcePosition()` (min page / clip start), NOT captureSeq — reading order |
| Source footer parse (pages/clip/bbox) | `utils/inboxParse.parseSourceRegion()` (`pages`, `startSeconds`/`endSeconds`, `local`, `bboxes`) |
| Learn Today/Week | `pages/LearnPage.jsx` `view` state |
| Inbox Enter-to-file / next-group | `organisms/InboxReview.jsx` `enterRef` effect / `load()` |
| Swipe threshold / keys | `organisms/FeedView.jsx SWIPE_PX` / `Stack` keydown effect |
| Feed sources panel | `organisms/FeedSources.jsx` |
| "Subscribe to this channel" card button | `organisms/FeedView.jsx isYouTubeUrl` + `FeedCard` → `store/useStore.js subscribeToChannel()` |
