# Store Flows

Files: useStore.js

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
  editorResetKey: number,        // increment to force Milkdown remount
  centerMode: 'view' | 'new',
  newNoteName: string,

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
Already in tabs[]? → switchTab(existingIndex)
New tab:
  _snapshotTab()
  openNote(fullPath)                   — fetch disk content
  push { path, pendingTitle, isMutable: false, hunks: [], pendingFiles: {} }
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

No disk write — unsaved changes are discarded silently.

The `activeTabIndex: -1` reset before switchTab is load-bearing: switchTab
early-returns when `index === activeTabIndex` (closing a non-last active tab
left them equal → editor kept showing the closed note), and `_snapshotTab`
must not write the closed tab's dirty state onto the tab taking its slot.

---

## syncNote()

```
1. Upload each pendingFile → POST /api/upload → revokeObjectURL → clear pendingFiles
2. pendingTitle changed? → PATCH /api/notes/rename → get newPath
3. computeHunks(currentNoteRaw, pendingRaw)
4. hunks non-empty? → PATCH /api/notes/content
5. currentNoteRaw = pendingRaw, isMutable = false
6. Update tab: path, hunks=[], isMutable=false
7. Refresh: fetchChildrenOf + fetchNoteNames + fetchReviewNotes
```

---

## cancelEdit()

```
Revoke all blob URLs; clear pendingFiles; setPendingBlobs({})
pendingRaw = currentNoteRaw
pendingTitle = noteBasename(currentNotePath)
isMutable = false
tabs[activeTabIndex].hunks = []
editorResetKey += 1              — forces Milkdown remount with clean body
```

No disk fetch needed — `currentNoteRaw` is still the saved version.

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

---

## Technology Notes

- **Zustand state is in-memory**: all tab state, pending edits, and blob URLs are lost on page refresh. There is no localStorage or sessionStorage persistence.
- **`editorResetKey`**: the only reliable way to force Milkdown to remount without a navigation. Incrementing it changes the `key` on `MilkdownProvider`, causing a full schema context teardown.
- **`setPendingBlobs` timing**: called in `useLayoutEffect` (before Milkdown's `useEffect`) to guarantee the blob registry is populated before `toDOM` runs during editor init.

---

## Change Index

| Thing to change | Where |
|---|---|
| Tab open / switch / close | `useStore.js openTab() / switchTab() / closeTab()` |
| Save flow | `useStore.js syncNote()` |
| Cancel / discard | `useStore.js cancelEdit()` |
| Dirty detection | `useStore.js syncNote()` `computeHunks` call |
| Drag-and-drop move | `useStore.js moveNote()` |
| New note mode | `useStore.js startNewNote() / cancelNewNote()` |
| Logout wipe list | `useStore.js initialDataState()` |
| Toast display timing | `useStore.js showToast()` (4 s timeout) |
| Persist tabs on refresh | Add Zustand persist middleware (not implemented) |
