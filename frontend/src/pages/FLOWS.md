# Pages Flows

Files: MainPage.jsx, LearnPage.jsx, ReviewPage.jsx, DashboardPage.jsx, SettingsPage.jsx

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
LearnPage → view toggle: Library | Inbox(badge=count)
  Library → LearnLayout(orientation, slotA, slotB)
    slotA: ResourcePanel (pdf | video | …) — resourceType state lives in LearnPage
    slotB: NotePanel
  Inbox   → InboxPanel(onCount) — triage queue for ingest-generated notes
orientation: landscape video → horizontal split; portrait video (short) &
             everything else → vertical
on mount (authed, no vaultRoot): fetchRootChildren() → fetchNoteNames()
inbox badge: fetchInbox().length, refreshed on mount + after each file/discard
```

- **Inbox** (`InboxPanel.jsx` + `api/inbox.js`): lists notes the ingest agent parked in
  `_inbox/` (see backend `inbox/FLOWS.md`). Select → edit markdown → pick a destination
  folder (datalist from `fetchChildren`, `_inbox` filtered out) → **Save & file**
  (`POST /api/inbox/file`) moves it into review, or **Discard** (`DELETE /api/inbox`).
- **Video orientation** is detected client-side from the `<video>` element's
  `videoWidth/videoHeight` on `loadedmetadata` (`ResourcePanel Viewer → onOrientation`),
  bubbled to `LearnPage` so a portrait short gets a vertical split. No ffprobe needed.

To change the split rule: `LearnPage.jsx → orientation` ternary
To add a resource type: `ResourcePanel.jsx` + orientation rule above
To change inbox triage: `InboxPanel.jsx` + backend `inbox/InboxController`

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

Flashcard tests vs self-rated slideshow (see cards FLOWS + frontend/FLOWS.md).

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
| Inbox triage UI | `organisms/InboxPanel.jsx` + `api/inbox.js` (backend `inbox/InboxController`) |
| Dashboard poll rate | `DashboardPage.jsx → POLL_MS` |
| Dashboard counters | `stats/StatsController.java` (backend) |
