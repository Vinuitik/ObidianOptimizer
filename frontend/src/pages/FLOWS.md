# Pages Flows

Files: MainPage.jsx, LearnPage.jsx, ReviewPage.jsx, DashboardPage.jsx, SettingsPage.jsx, GetAppPage.jsx, PipelineFailuresPage.jsx

---

## MainPage — Startup

`MainPage` mounts → `useEffect` sequence:
```
loadSettings() → GET /api/settings → store.settings
  .then(initReviewSession)          — uses reviewPageSize from settings
parallel: checkAuth() / fetchRootChildren() / fetchNoteNames()
```

`MainPage` renders `SplitLayout` (left: FolderTree, center: MilkdownEditor or NewNoteForm, right: ReviewList) + `LoginModal` (shown when `showLogin` is true).

To change startup data-fetch order: `MainPage useEffect`

---

## SettingsPage — Settings Form

`SECTIONS` config array drives all setting rows. Add new settings by adding entries there.

On mount: local draft state initialised from `store.settings` (set by `loadSettings()` at startup).  
Each section has its own "Save" button — active only when draft differs from `store.settings`.

Save → `applySettings(patch)` → `PUT /api/settings` → `store.settings` updated → review queue re-fetched if `reviewPageSize` changed.

`vaultPath` change: backend re-indexes (TRUNCATE + full BFS) — may be slow on large vaults.

Number field validation: `field.max` present → `[min, max]`; absent → `>= min` only.

To add a new setting:
1. Entry in `SettingsPage.SECTIONS[].fields` (`key`, `label`, `type`, optional `hint`, optional `max`)
2. `SettingsRepository.java` typed getter + default
3. `SettingsController.SettingsResponse` + `UpdateSettingsRequest` records
4. `SettingsController.updateSettings()` handler

---

## PipelineFailuresPage — `/failures` (top-level nav)

Read-only debugging ledger over the shared `pipeline_failures` table
(`architecture_plans/QUEUE_UNIFICATION_PLAN.md`) — every backend pipeline dead-letter
(capture retry-ladder exhausted, ingest stage failures, the browser extension's own
client-side dead-ends) lands here instead of vanishing silently. No generic retry button —
payload shapes differ per source/stage, so replay is source-specific (e.g. capture's own
`/capture/{id}/retry` on the `SyncPage` failed-list, a *different*, narrower view scoped to
just the `capture` table's retryable rows).

Mount → `fetchPipelineFailures({onlyOpen})` (`api/pipelineFailures.js`) → `GET
/pipeline-failures`. Row click expands the full `input_payload` JSON
(`JSON.parse`/pretty-print client-side — the column is JSONB text over the wire). "Mark
resolved" → `POST /pipeline-failures/{id}/resolve`, removes it from the (default) open view.
Filters: open-only toggle, source dropdown (populated from whatever's actually loaded, not
a hardcoded list).

To change what's captured here: the WRITE side lives per-pipeline, not on this page —
`common/PipelineFailureRepository.record()` (Java) / `embedder/failures.py
record_failure()` (Python) / the extension's `background.js reportFailure()` (client-side
dead-ends only, see `extension/FLOWS.md`). To change: `PipelineFailuresPage.jsx`
(`summarize()` for the collapsed-row heuristic), `api/pipelineFailures.js`.

### Google Drive Sync panel

`DriveSyncPanel` (custom section, not in `SECTIONS`): status from `GET /api/sync/status`
(`api/sync.js`), OAuth client id/secret + passphrase fields (secrets write-only —
placeholder shows "saved"), auto-sync toggle, **Connect Google Drive** (redirects to the
consent URL from `/api/sync/oauth/url`), Disconnect, Sync now / Pull from Drive.
Returning from Google lands on `/settings?drive=connected|error` — the panel reads and
strips the param. Backend flow: `sync/FLOWS.md` → "Auth & configuration".

### Chrono Status Panel

Rendered below the `SECTIONS` loop — not a form field, so not in the array.

State: `chronoStatus` (from `GET /api/chrono/status` on mount), `chronoRunning`, `chronoResult`, `chronoError`.  
"Run now" → `POST /api/chrono/run` → updates result + status.  
Button disabled when `!isAuthenticated` or `chronoRunning`.

Result fields: `filesMoved`, `bankruptcy` (`overdueCount`, `chronicNeglected`, `declared`, `rescheduled`), `spread.moved`.

To add more result fields: `ChronoService.ChronoResult` record + update display here.

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
  - **NEW** = the editable note: Edit/Preview, Preview = `NoteRenderer` (shared **read-only
    Milkdown** — GFM tables, `![[images]]`, math, code; identical to the main editor).
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

Flashcard tests vs self-rated slideshow (see cards FLOWS + frontend/FLOWS.md). The inline
"Review note directly →" view (`InlineNoteReview`, shown when a note has no cards yet)
renders through the shared `molecules/NoteRenderer` (read-only Milkdown — the SAME pipeline
as the main editor, so tables/images/math/code render identically), not raw `<pre>`, AND
carries a bottom grade bar: the four FSRS bands (`BANDS`: HARD/GOOD/EASY/VERY_EASY) →
`gradeNote()` → `POST /api/reviews/grade` → `dismissFromReview` — so a note with no
generated cards is still actually rescheduled, not just read. `inlineNote` must carry
`fullPath` (set in `handleReviewNote`) for the grade call.
(`molecules/MarkdownContent` is the older markdown-it renderer — kept for the mobile/lightweight
path; the rich Milkdown reuse is `NoteRenderer`, since the main note surface is Milkdown, not
markdown-it. `NoteViewer.jsx` is legacy/unused.)

**One review system (`settings.flashcardsEnabled`)** — mutually exclusive, replaces the old
local `reviewMode` pref:
- ON: Review tab shown (`NavBar` filters `flashcardsOnly` items), main-page review list hidden
  (`SplitLayout showRight=!flashcardsEnabled`), flashcards generated (backend `CardJobWorker`).
- OFF: Review tab hidden, review list runs inline in the Notes right panel, **no new cards**
  (existing kept). To change: `SettingsPage` `flashcardsEnabled` field + the three gates above.

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

## Change Index

| Thing to change | Where |
|---|---|
| Startup data-fetch order | `MainPage.jsx useEffect` |
| Page routes | `App.jsx <Routes>` + `NavBar.jsx NAV_ITEMS` |
| Settings sections / fields | `SettingsPage.jsx SECTIONS` array |
| Chrono result display | `SettingsPage.jsx` chrono status section |
| Learn split orientation | `LearnPage.jsx` orientation ternary (video orient via `ResourcePanel` Viewer) |
| Learn Library/Inbox toggle | `LearnPage.jsx` `view` state |
| Inbox triage UI | `organisms/InboxReview.jsx` + `api/inbox.js` (backend `inbox/InboxController`) |
| Inbox folder tree grouping | `utils/sourceColor.js` (`buildInboxTree`, `folderAllItems`) |
| File a whole folder/chapter | `InboxReview.jsx` (`fileGroup`, `openFolderFilePicker`, `openChapterFilePicker`) |
| Dashboard poll rate | `DashboardPage.jsx → POLL_MS` |
| Dashboard counters | `stats/StatsController.java` (backend) |
| Get-app / get-extension CTAs | `GetAppPage.jsx` (device detection via `pwa/installPrompt.js`) |
| Chrome extension download zip | `linux_scripts/build-chrome-extension-zip.sh` → `ext-dist/obsidian-optimizer-chrome.zip` |
