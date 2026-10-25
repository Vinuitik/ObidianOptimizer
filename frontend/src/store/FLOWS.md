# Store Flows

Files: useStore.js, flashSessionPersist.js

---

## State Shape

```js
{
  // Note state
  currentNotePath: string | null,
  currentNoteRaw: string,        // last-saved disk content
  pendingRaw: string,            // live editor content (may differ from disk)
  pendingFrontmatter: object,
  pendingTitle: string,
  isMutable: boolean,
  editorResetKey: number,        // increment to force CmEditor remount
  centerMode: 'view',            // vestigial (the 'new' form is gone)
  saveStatus: 'saving' | 'saved' | 'error' | null,
  titleFocusNonce: number,       // bump -> SplitLayout focuses+selects the title input

  // Tab state
  tabs: Tab[],
  activeTabIndex: number,

  // Tree state
  tree: object,
  noteIndex: Map<string, string>, // basename.lower() → full path

  // Review state
  reviewNotes: Note[],
  reviewHasMore: boolean,

  // Settings
  settings: object,

  // Auth
  isAuthenticated: boolean,
  showLogin: boolean,

  // Paste-upload
  pendingFiles: { [filename]: { file: File, blobURL: string } },

  // Toast
  toastMessage: string | null,
}
```

Each `Tab`:
```js
{ path, pendingTitle, isMutable, hunks: Hunk[], pendingFiles: { [fn]: {file,blobURL} } }
```

---

## openTab(fullPath)

```
await flushSave()                    — previous note never left unsaved
Already in tabs[]? → switchTab(existingIndex)
New tab:
  _snapshotTab()
  openNote(fullPath)                   — fetch disk content
  push { path, pendingTitle, isMutable: true, hunks: [], pendingFiles: {} }   — edit by default
  activeTabIndex = newTabs.length - 1
```

`openNote` is internal. Do not call from UI.

---

## _snapshotTab()

Called before leaving the current tab. Saves:
- `computeHunks(currentNoteRaw, pendingRaw)` → `tabs[activeTabIndex].hunks`
- `pendingTitle`, `isMutable` → tab entry

No-op if `activeTabIndex < 0`.

---

## switchTab(index)

```
await flushSave()                    — editor debounce pulled synchronously, then saved; failure keeps hunks in the snapshot
_snapshotTab()
raw = fetchNoteContent(tab.path)     — always re-fetches from disk
if tab.hunks.length > 0:
  pendingRaw = applyHunks(raw, tab.hunks)
else:
  pendingRaw = raw
set: currentNoteRaw, currentNotePath, pendingRaw, pendingFrontmatter,
     pendingTitle, isMutable, activeTabIndex
setPendingBlobs(tab.pendingFiles)    — restore blob registry
```

Re-fetch on every switch picks up external changes. `applyHunks` falls back to disk version if hunks are stale.

---

## closeTab(index)

```
Remove from tabs[]
Revoke all blobURLs in closing tab's pendingFiles
If was active + more tabs remain → set activeTabIndex: -1, THEN switchTab(min(index, newLen-1))
If was active + no tabs remain   → clear all editor state
If was not active                → adjust activeTabIndex
```

Closing the ACTIVE tab runs `flushSave()` first (autosave); a non-active tab's unsaved hunks are discarded.

The `activeTabIndex: -1` reset before switchTab is load-bearing: switchTab
early-returns when `index === activeTabIndex` (closing a non-last active tab
left them equal → editor kept showing the closed note), and `_snapshotTab`
must not write the closed tab's dirty state onto the tab taking its slot.

---

## Autosave: flushSave() / scheduleAutosave() / syncNote()

```
keystroke → CmEditor 120 ms debounce → updatePending(body) → (isMutable) scheduleAutosave()
  → 1500 ms idle (AUTOSAVE_MS) → flushSave()
flushSave() (also: tab/note switch, toggle→reading, window blur, visibilitychange hidden,
             beforeunload, Mod-s, title commit, "Save failed — retry" click)
  → editorFlush()            — CmEditor's debounce, registered via setEditorFlush(); runs FIRST, synchronously
  → clear idle timer
  → save in flight? saveAgain = true; return same promise      (never two syncNote at once)
  → not dirty (selectDirty)? resolve
  → loop { saveStatus='saving'; syncNote() } while (saveAgain && dirty) → saveStatus='saved'
  → throw: saveStatus='error', toast, state stays dirty, REJECTS (callers swallow with .catch)
syncNote():
  1. Upload each pendingFile → POST /api/upload → revokeObjectURL → clear pendingFiles
  2. pendingTitle changed? → PATCH /api/notes/rename → newPath
  3. computeHunks(currentNoteRaw, pendingRaw) non-empty? → PATCH /api/notes/content
  4. currentNoteRaw = the SAVED snapshot (edits typed meanwhile stay dirty); isMutable untouched
  5. Update tab: path, hunks=[]; refresh fetchChildrenOf + fetchNoteNames + fetchReviewNotes
```
`toggleMutable()` leaving edit mode calls `flushSave()` before flipping. `commitTitle(t)` (title blur/Enter) sets `pendingTitle` then `flushSave()`; blank/unchanged ignored. Rename changes `currentNotePath` → CmEditor remounts (cursor/undo reset).
To change idle delay: `useStore.js AUTOSAVE_MS`. Dirty rule: `useStore.js selectDirty()`.

## startNewNote(folder?) — instant create

```
folder = arg (clicked +) → dirname(currentNotePath) → vaultRoot
name   = nextUntitledName(noteIndex)         — "Untitled", "Untitled 2"… vault-wide basename check
createNote(folder, name) → apiCreate → fetchChildrenOf → fetchNoteNames → openTab (edit mode)
titleFocusNonce += 1                         — SplitLayout focuses + selects the title
```
No "selected folder" state exists in FolderTree: the folder is the `+` that was clicked; header-level New note passes vaultRoot.
To change naming: `useStore.js nextUntitledName()`.

---

## logout()

```
POST /logout (server session invalidated)
revoke all pendingFiles blobURLs; setPendingBlobs({})
localStorage.removeItem(REVIEW_KEY)        — review session offset
set({ ...initialDataState(), isAuthenticated: false, editorResetKey+1 })
```

`initialDataState()` is the single factory for all vault/backend-loaded state —
store creation and logout both spread it, so the wipe list can't drift from the
field list. UI prefs survive (reviewMode, panel collapse): not vault data.

To add a field that must be wiped on logout: put it in `initialDataState()`.

---

## moveNote(sourcePath, targetFolder)

`PATCH /api/notes/move` → `{ path: newAbsPath }` → update open tab path if source matches → `fetchChildrenOf` both source parent and target folder → `fetchNoteNames()`

## Feed (Learn ▸ Feed)
Files: useStore.js (feed* slice), api/feed.js
`decideFeed('accept'|'bin')` → pop `feedPending[0]` (card gone instantly) + set `feedLastDecision` → `POST /feed/items/{id}/accept|bin`
→ ok: `setFeedNotice('Sent to Inbox','view')` / `('Binned.','undo')` (global Toast; View → `setLearnView('inbox')`) ‖ fail: card back at front + error toast ("Couldn't accept — try again.").
`undoFeed()` → `restoreFeedItem(lastDecision.item)` → `POST .../restore` → 409: "Already ingesting — discard it in Inbox."
`restoreFeedItem(item)` = Undo only. The bin list lives in Settings ▸ Bin (`pages/BinPanel.jsx`); `fetchFeedBin`/`ingestFeedItem`/`feedBinned` removed.
Sources: `fetchFeedSources / addFeedSource(url) → POST /feed/sources / setFeedSourceAutoAccept(id, true|false|null) / deleteFeedSource / checkFeedSource (poll, 202)`.
Toast: `setFeedNotice(message, 'undo'|'view'|null)` → global `showToast` (`feedNotice` state is gone). `feedLoaded` = first fetch settled (gates skeleton vs empty); `learnView` = legacy Learn tab flag; LearnPage no longer reads it (Today|Week is local state).
`deleteFeedSource(id)` is optimistic + 7 s Undo toast (`DELETE_UNDO_MS`; the DELETE is only sent after the window). `resolveFeedSource(url)` = subscribe preview (`POST /feed/sources/resolve`, nothing persisted).
To change toast timing: `showToast` duration / `DELETE_UNDO_MS`. To change swipe threshold: `FeedView.jsx SWIPE_PX`.

---

---

## Technology Notes

- **Zustand state is in-memory**: all tab state, pending edits, and blob URLs are lost on page refresh. There is no localStorage or sessionStorage persistence.
- **`editorResetKey`**: the only reliable way to force the CM6 editor to rebuild its `EditorView` without a navigation (`CmEditor` effect deps `[currentNotePath, editorResetKey]`). A pending debounce timer is dropped on teardown; callers (`flushSave`) pull it via `setEditorFlush` BEFORE anything unmounts the editor.
- **`setPendingBlobs` timing**: called in `CmEditor`'s `useLayoutEffect` (before the view-creating `useEffect`) so the blob registry is populated before embed widgets' `toDOM` runs.

- **Feed state is in-memory and optimistic**: lost on refresh (re-fetched from the server by `fetchFeed()`); a decision whose request is still in flight is rolled back only if the request fails — close the tab mid-flight and the server may or may not have it.
- **Undo is last-decision only**: `feedLastDecision` holds one item; a second swipe replaces it. Accept-undo works only while the capture is still QUEUED (else 409).
- `fetchFeed` loads `limit=50` pending; the badge counts loaded items, not the server total.

---

## Change Index

| Thing to change | Where |
|---|---|
| Tab open / switch / close | `useStore.js openTab() / switchTab() / closeTab()` |
| Save pipeline | `useStore.js flushSave() / syncNote()` |
| Autosave delay | `useStore.js AUTOSAVE_MS` |
| Dirty detection | `useStore.js selectDirty()` |
| Edit/reading toggle | `useStore.js toggleMutable()` |
| Title rename | `useStore.js commitTitle()` |
| Drag-and-drop move | `useStore.js moveNote()` |
| New note (instant) | `useStore.js startNewNote() / nextUntitledName()` |
| Logout wipe list | `useStore.js initialDataState()` |
| Toast display timing | `useStore.js showToast()` (4 s timeout) |
| Persist tabs on refresh | Add Zustand persist middleware (not implemented) |
| Feed swipe / undo / rollback | `useStore.js decideFeed() / undoFeed() / restoreFeedItem()` |
| Feed sources actions | `useStore.js addFeedSource() / setFeedSourceAutoAccept() / deleteFeedSource() / checkFeedSource()` |
| Feed page size | `api/feed.js fetchFeedItems(limit=50)` |
