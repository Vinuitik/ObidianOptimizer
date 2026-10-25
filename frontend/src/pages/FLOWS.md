# Pages Flows

Files: MainPage.jsx, SourcesPage.jsx, LearnPage.jsx, ReviewPage.jsx, DashboardPage.jsx, SettingsPage.jsx, GetAppPage.jsx, PipelineFailuresPage.jsx

---

## MainPage — Startup

`MainPage` mounts → `useEffect` sequence:
```
loadSettings() → GET /api/settings → store.settings
  .then(initReviewSession)          — uses reviewPageSize from settings
parallel: checkAuth() / fetchRootChildren() / fetchNoteNames()
```

`MainPage` renders `SplitLayout` (left: FolderTree, center: CmEditor or reading-view MarkdownView, right: ReviewList) + `LoginModal` (shown when `showLogin` is true).

To change startup data-fetch order: `MainPage useEffect`

---

## SettingsPage — autosaving, grouped (`/settings`)

Files: SettingsPage.jsx, useAutosaveSettings.js, SettingsPage.module.css

Groups (Card each): Vault · Learning · Feed · Sync (`DriveSyncPanel`) · Advanced. Fields are
the `F` map at the top of `SettingsPage.jsx`; render via `renderField(F.x)` inside a `Group`.

Edit a simple field → `useAutosaveSettings.setDraft()` → select: `commit()` at once; text/number: `commit()` on blur/Enter
→ `parseField()` validates (inline error, no request) → unchanged value = no-op → `applySettings(patch)` → `PUT /api/settings`
→ `store.settings` updated → inline "Saved ✓" (2s; `SAVED_MS`). Failure → inline "Couldn't save" under the field.
To change validation: `parseField()` · save delay/UX: `useAutosaveSettings.js`.

Explicit buttons only: **Drive **credentials** (`DriveSyncPanel.saveCreds()`). Resources folder: Browse… autosaves on pick.
Long paths/URIs wrap (`.mono`, `.uri`) with a `CopyButton`. Host-only items (embedding model) carry a one-sentence reason.

Vault folder is read-only here; vault switching REMOVED pending multi-vault (architecture_plans/VAULTS_PLAN.md).

To add a new setting:
1. Field in `SettingsPage.F` + `renderField(F.x)` in the right group
2. `SettingsRepository.java` typed getter + default
3. `SettingsController.SettingsResponse` + `UpdateSettingsRequest` records
4. `SettingsController.updateSettings()` handler

### Google Drive Sync group

`DriveSyncPanel`: status from `GET /api/sync/status` (`api/sync.js`), OAuth client id/secret + passphrase
(write-only; hidden once set, "Edit" reveals), auto-sync select (autosaves), **Connect Google Drive**
(redirect from `/api/sync/oauth/url`), Full backup / Restore / Disconnect. Returning from Google lands on
`/settings?drive=connected|error` — the panel reads and strips the param. Backend: `sync/FLOWS.md`.

### Nightly jobs (Advanced)

`chronoStatus` (`GET /api/chrono/status`), "Run now" → `POST /api/chrono/run` → result line.

---

## PipelineFailuresPage — `/failures` (top-level nav)

Files: PipelineFailuresPage.jsx, ../api/capture.js, ../api/pipelineFailures.js

Two independently-loaded sections (each Skeleton → list | EmptyState | `ErrorState`+Retry; an error never shows a "clean" message):
- **Failed captures**: mount → `fetchFailedCaptures()` → `GET /capture/failed`. Row **Retry** → `retryCapture(id)` (`POST /capture/{id}/retry`),
  **Dismiss** → `dismissCapture(id)`; row leaves the list + toast. Handler: `handleCaptureAction()`.
- **Pipeline failures**: `fetchPipelineFailures({onlyOpen})` → `GET /pipeline-failures` (shared `pipeline_failures` ledger,
  `QUEUE_UNIFICATION_PLAN.md`). "Show details" expands the payload JSON; "Mark resolved" → `resolvePipelineFailure(id)`.
  **Resolve all older than 7 days** → `resolveOlder()` loops the per-row resolve (`WEEK_MS`); partial failures stay listed + toast.

Nav badge: `store.failuresCount` (open pipeline failures + failed captures) ← `store.loadFailuresCount()`, called on NavBar mount
(authenticated) and after every retry/dismiss/resolve. Shown on the Failures link in `NavBar.jsx` only (desktop; phone tab bar has none).

WRITE side lives per pipeline: `common/PipelineFailureRepository.record()` (Java) / `embedder/failures.py record_failure()` /
extension `background.js reportFailure()`. To change summary heuristic: `summarize()`; labels: `humanize()`.

---

## LearnPage — Split-view study (`/learn`)

Auth-gated ("Sign in to use Learn" when logged out).

```
LearnPage → InboxReview — the ingest review IS Learn now.
on mount (authed, no vaultRoot): fetchRootChildren() → fetchNoteNames()
```
The old **Library** view (`LearnLayout` + `ResourcePanel` manual `_workspace/` media shelf +
`NotePanel`) predated the capture→ingest→inbox pipeline and was vestigial — removed. Those
components stay in-tree, unimported (restore by reinstating the view toggle in `LearnPage`).

- **Inbox review** (`InboxReview.jsx` + `api/inbox.js`): the ingest consume layer
  (INGESTION_V2_FLOWS §7). Layout REUSES the old Library shell the user liked:
  `[collapsible queue] · LearnLayout( ORIGINAL | NEW ) · [proposed-folder bar]`.
  - **The queue itself is now a collapsible folder tree, not a flat list**
    (`utils/sourceColor.js buildInboxTree` + `InboxReview.jsx renderTree`). Standalone notes
    group into a source-folder node (color band + note count + a "📁 <suggestion> · File
    folder" button, from `groupSuggestedFolder`) — collapsed by default. A PDF source with a
    real embedded ToC nests one level deeper into chapter sub-nodes (`chapterSuggestedFolder`),
    same collapse/file affordance. "File folder" opens `FolderPicker` seeded at the group's
    suggestion, then `fileGroup()` loops the ordinary per-note `fileInboxOffline` action (no
    new backend call) for every member note into one shared `Dest/<sourceTitle>[/<chapter>]/`
    — reuses the existing offline-queue-aware file action as-is. in-place notes and any
    legacy captureId-less note render as plain leaf rows, unchanged from before.
    Selecting a note auto-expands its ancestor folder/chapter so the active row never
    disappears behind a collapsed group. Backend: `inbox/FLOWS.md` "Per-source staging
    folders" (embedder side) + `GET /inbox` group-suggestion fields (Java side).
  - `LearnLayout` gives the adjustable, swappable, **orientation-aware** split for free —
    landscape video → horizontal, else vertical. `SourceSplicePanel` reports orientation via
    `onOrientation` (YouTube→landscape; local `<video>` from `loadedmetadata`; else vertical).
  - **ORIGINAL** = `SourceSplicePanel` (read-only): `parseSourceRegion` reads the note's `##
    Source` footer → YouTube embed seeked to the timestamp / `<video>#t=` / PDF `#page=` /
    **text → `RsvpReader`** (fast one-word ORP reading, §7).
  - **NEW** = the editable note: Edit/Preview, Preview = `MarkdownView` (read-only markdown-it —
    GFM tables, `![[images]]`, math, code, wiki-links).
  - **Bottom bar**: the **proposed** folder (find_home), re-picked from an animated folder
    tree (`FolderPicker` modal) → **Save & file** / **Discard**, or **Save & acknowledge**
    for in-place notes. Filing moves the note into its folder + the FSRS queue.
  The panels render the LIVE draft (source/preview reflect edits). The injected `## Sequence`
  / `## Related` links live in the note itself (visible in Preview) — the standalone
  `LinksPanel.jsx` is unused now (kept in-tree). Superseded: `InboxPanel.jsx` (old 2-pane).
  Pure parse/RSVP logic: `utils/inboxParse.js`, `utils/rsvp.js` (tested).

To change the split rule: `InboxReview.jsx → orientation` ternary (+ `SourceSplicePanel.onOrientation`)
To change inbox triage: `InboxReview.jsx` (+ `SourceSplicePanel`/`RsvpReader`/`FolderPicker`) + backend `inbox/InboxController`
To change the shared note renderer: `molecules/MarkdownContent.jsx` (wraps `utils/markdown.renderMarkdown`)

---

## DashboardPage — Processing dashboard (`/dashboard`)

Live view of async AI processing. Polls `GET /api/stats`
every 3s (`POLL_MS`), pauses while `document.hidden`, shows stale indicator on
fetch errors (keeps last data).

```
fetchStats() → { embedding, images, flashcards, resources, wrapper }
  → Note Embedding donut (recharts Pie): notesEmbedded / notesTotal
  → Image Processing donut: done / pending / skipped (the multi-day queue)
  → Flashcard Coverage bar: notesWithCards / eligibleNotes (+ active/archived)
  → LLM Providers table: per-provider state (ready/working/cooling/no key)
      from host-wrapper /providers, proxied by StatsController
  → Video & Resource Queue: ingest jobs (embedder /ingest via StatsController), counts + recent list
```

To change poll rate: `DashboardPage.jsx → POLL_MS`
To add a chart: section in DashboardPage + counter in `StatsController.java`

---

## ReviewPage

Fast loop (W1): mount → `load()` (`initReviewSession`, Skeleton → ready | ErrorState+Retry) → effect auto-opens
`pickNext(reviewNotes)` (interleaved, `reviewOrder.js`) via `startSession` (once; re-arms when queue empties) → read-track: keys 1-4 = Hard/Good/Easy/Very easy,
Enter = Good (`InlineNoteReview` window keydown; ignored in inputs/focused buttons) → `completeReview` → "Graded X" row
(`flash`) → after `ADVANCE_MS` (800) `handleClose` → next note (`pickNext` = `pickNextInterleaved`: track by remaining ratio, DFS within track). Flashcard result: primary
auto-focused "Next note →" (`FlashcardSession`) → `onClose` → same advance. Queue empty → `EmptyState` "All caught up".
List order = server's oldest-due-first (payload has no due dates, so no client sort).
To change auto-advance delay: `ADVANCE_MS`. To change keys: `BANDS` + `InlineNoteReview` onKey.

Flashcard tests vs self-rated slideshow (see cards FLOWS + frontend/FLOWS.md). The inline
"Review note directly →" view (`InlineNoteReview`, shown when a note has no cards yet)
renders through the shared `molecules/MarkdownView` (read-only markdown-it: tables/images/math/code), not raw `<pre>`, AND
carries a bottom grade bar: the four FSRS bands (`BANDS`: HARD/GOOD/EASY/VERY_EASY) →
`gradeNote()` → `POST /api/reviews/grade` → `dismissFromReview` — so a note with no
generated cards is still actually rescheduled, not just read. `inlineNote` must carry
`fullPath` (set in `handleReviewNote`) for the grade call.
(`molecules/MarkdownContent` is the older markdown-it renderer; `NoteViewer.jsx` is legacy/unused.)

**One review system (`settings.flashcardsEnabled`)** — mutually exclusive, replaces the old
local `reviewMode` pref:
- ON: Review tab shown (`NavBar` filters `flashcardsOnly` items), main-page review list hidden
  (`SplitLayout showRight=!flashcardsEnabled`), flashcards generated (backend `CardJobWorker`).
- OFF: Review tab hidden, review list runs inline in the Notes right panel, **no new cards**
  (existing kept). To change: `SettingsPage` `flashcardsEnabled` field + the three gates above.


**Flashcard session persistence (tab switch / app kill)** — `FlashcardSession` mirrors its state
(assignment, idx, answers, flagged, phase, verdicts, completion) into `store/flashSessionPersist.js`
(localStorage `obsOpt_flashSession`) on every change → `ReviewPage` mount: `restoreActiveNote()` reads it
into `activeNote` (so the auto-open effect doesn't pick another note) → `FlashcardSession` mount hydrates
from it, skips `buildAssignment` → Finish: `finish()` is idempotent per assignment id (in-memory ref +
persisted `grading`/`phase:'result'`), writes the result record directly (lands even if unmounted
mid-grading); a remount during grading polls storage instead of re-submitting → "Close"/"Next note"/"Cancel"
→ `clearFlashSession`. "Review note directly" keeps it (back-tab shows the result again).
`completeReview` → `markNoteDone` → `fetchReviewNotes` filters those paths (Drive mode: IDB due-list
still has the note until sync).
Original blank cause: all session state was component-local; remount → `activeNote=null`, note already
dismissed from `reviewNotes` → "All caught up"/empty pane (or a different note auto-opened).
To change: `restoreActiveNote()`, `FlashcardSession` persist effect / `finish()` guard.

**Technology Notes (persistence)**: localStorage, not sessionStorage — the installed PWA can be killed by
the OS and sessionStorage dies with the tab/process. One active session only, keyed by notePath +
assignment id, day-scoped (`date`; yesterday's is ignored). Size: the whole assignment (cards+variants,
tens of KB) is stored; quota errors are swallowed (best effort, degrades to old behavior). Staleness:
a session abandoned without Close resumes next time the same day; a `grading` marker older than 60 s
(`GRADING_STALE_MS`) is treated as a dead app and re-finish is allowed (answers can then be re-sent;
server verification is per card/assignment). Not shared across devices; lost if site data is cleared.
MCQ options ignore touch presses >500 ms or moved >10 px (`McqOptions`); mouse/keyboard unaffected.

---

## GetAppPage — Get app / Get extension (`/get-app`)

Two independent cards, each with a single device-detected CTA (no user choice needed —
see `pwa/FLOWS.md` for the detection helpers `isWindows`/`isIOS`/`isFirefox`/`isElectron`
imported from `installPrompt.js`).

**Install as app** card: Windows → `/download/ObsidianOptimizer-Setup.exe` (Electron,
unsigned, see `desktop/FLOWS.md`) · installed → status line only · install-prompt
available → native `promptInstall()` · else → "How to install" reveals manual steps.

**Browser extension** card — `isFirefox()` branches the whole CTA:
- **Firefox**: `GetAppPage` fetches `/ext/updates.json` on mount → takes the last
  `updates[]` entry's `update_link` → renders it as a plain `<a href>` to the signed
  `.xpi`. No `download` attribute — Firefox's `application/x-xpinstall` mime type
  (declared in nginx's `/ext/` block) makes it intercept the navigation and show its own
  install-confirm dialog instead of downloading the file. Same signed artifact
  `linux_scripts/deploy-extension.sh` publishes; nothing extra to build per release.
- **Chrome/Edge/Brave**: `<a download>` to `/ext/obsidian-optimizer-chrome.zip` (built by
  `linux_scripts/build-chrome-extension-zip.sh` — re-run after any `extension/` edit
  that should reach this download) + inline numbered "Load unpacked" instructions.
  Chrome refuses to install anything from outside the Chrome Web Store, signed or not —
  there's no self-hosted-auto-install path for it like Firefox's, so this is the
  honest ceiling until/unless the extension is published to the Web Store (see
  `architecture_plans/EXTENSION_ARCH.md`).

To change either CTA: `GetAppPage.jsx` (the two `<section>` blocks). To change what's in
the Chrome zip: `build-chrome-extension-zip.sh` (excludes `manifest.firefox.overlay.json`
and the dev-facing `FLOWS.md`, keeps `README.md`).

---

### Learn ▸ Today | Week
`LearnPage` view state (default `today`): Today = `InboxReview` first, `TodayPlan` below; Week = `WeekPlan` (see frontend/FLOWS.md "Learn — Today | Week"). Feed lives on `SourcesPage` (`/sources`: Feed | Capture tabs; see frontend/FLOWS.md "Sources").
Settings ▸ Feed → `feedAutoAccept` select (`SettingsPage.jsx` `F.feedAutoAccept`) → `PUT /settings` (global default; per-source override in Sources ▸ Feed ▸ Subscriptions).

---

## Settings ▸ Vaults
`SettingsPage` renders `VaultsPanel.jsx` first (list, register via host-fs `FolderPicker` + downtime confirm, switch, rename, archive/restore; no delete) — details in [../vault/FLOWS.md](../vault/FLOWS.md). `DriveSyncPanel` is the ACTIVE vault's sync and names it.

## Change Index
| Vaults settings section | `VaultsPanel.jsx`, `SettingsPage.jsx` (mount point + `DriveSyncPanel` description) |

| Thing to change | Where |
|---|---|
| Startup data-fetch order | `MainPage.jsx useEffect` |
| Page routes | `App.jsx <Routes>` + `NavBar.jsx NAV_ITEMS` |
| Settings groups / fields | `SettingsPage.jsx` `F` map + `Group` blocks |
| Settings autosave / validation | `useAutosaveSettings.js` (`commit`, `parseField`) |
| Failures badge count | `store.loadFailuresCount()` |
| Failed-capture retry/dismiss | `PipelineFailuresPage.handleCaptureAction()` |
| Chrono result display | `SettingsPage.jsx` Advanced group |
| Learn split orientation | `LearnPage.jsx` orientation ternary (video orient via `ResourcePanel` Viewer) |
| Learn Today/Week toggle | `LearnPage.jsx` `view` state |
| Inbox triage UI | `organisms/InboxReview.jsx` + `api/inbox.js` (backend `inbox/InboxController`) |
| Inbox folder tree grouping | `utils/sourceColor.js` (`buildInboxTree`, `folderAllItems`) |
| File a whole folder/chapter | `InboxReview.jsx` (`fileGroup`, `openFolderFilePicker`, `openChapterFilePicker`) |
| Dashboard poll rate | `DashboardPage.jsx → POLL_MS` |
| Dashboard counters | `stats/StatsController.java` (backend) |
| Get-app / get-extension CTAs | `GetAppPage.jsx` (device detection via `pwa/installPrompt.js`) |
| Chrome extension download zip | `linux_scripts/build-chrome-extension-zip.sh` → `ext-dist/obsidian-optimizer-chrome.zip` |
| `feedAutoAccept` setting | `SettingsPage.jsx` `F.feedAutoAccept` |
| Flashcard session persistence | `store/flashSessionPersist.js`, `FlashcardSession.jsx` persist effect + `finish()`, `ReviewPage.jsx restoreActiveNote()` |
| Done-today filter (Drive mode) | `useStore.js completeReview()` → `markNoteDone`; `fetchReviewNotes` → `doneNotePaths` |
| MCQ long-press/scroll guard | `FlashcardSession.jsx McqOptions` (`LONG_PRESS_MS`, `MOVE_PX`), CSS `.option` |
| Flag form per card | `FlashcardSession.jsx` `<FlagControl key={card.id}>` |

## Flow — review order (interleaved flashcard / read)
Files: reviewOrder.js, ReviewPage.jsx
Auto-open + `handleClose` → `ReviewPage.pickNext(notes, donePath)` → `reviewOrder.pickNextInterleaved(notes, donePath, orderHist.current)` → `nextTrack(C, R, hist)` (C/R = remaining flashcard/read counts, recomputed every call, no stored plan) → `pickNextInOrder(thatTrackOnly, donePath)` (DFS, wraps) → `advanceHistory` updates the `orderHist` ref `{lastTrack, run}`.
- Rule: nothing yet → card; after a card → read; in a read run → card once `run >= round((R+run)/(C+1))`. 2 cards/8 reads → C RRRR C RRRR; 3/3 → CRCRCR; C=0 or R=0 → plain DFS. To change: `reviewOrder.nextTrack()`.
- History lives only in a ref (session-local, lost on reload → ratio just recomputes from what is left; manual note clicks don't update it). Left-hand list keeps server order.

| Touchable | Where |
|---|---|
| Interleave rule | `reviewOrder.nextTrack()` |
| Within-track order | `reviewOrder.pickNextInOrder()` / `comparePaths()` |
| Call sites | `ReviewPage.pickNext`, auto-open effect, `handleClose` |

## Settings ▸ Bin
`SettingsPage` renders `BinPanel.jsx` after Sync: `fetchBin()` → segments (Notes·Folders·Files·Feed·Tracks[+track_item]·Subscriptions·Other[unknown]; 0-item segments hidden, counts shown) → row Restore → `restoreBinItem()` (404/409/410 → `RESTORE_ERRORS` messages) · Delete forever → confirm → `deleteBinItem()` · Empty bin → confirm → `emptyBin()`. Backend 30-day purge. Sources has no Bin tab; swipe-left still bins.
To change: segment grouping `BinPanel.SEGMENTS` · error copy `BinPanel.RESTORE_ERRORS` · client `api/bin.js`.

| Bin settings section | `BinPanel.jsx`, `api/bin.js`, mount in `SettingsPage.jsx` |
