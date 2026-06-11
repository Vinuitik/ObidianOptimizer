# Pages Flows

Files: MainPage.jsx, SettingsPage.jsx, ReviewPage.jsx

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

Result fields: `filesMoved`, `filesFixed`, `overdueCount`, `bankruptcy` flag, moved-notes count.

To add more result fields: `ChronoService.ChronoResult` record + update display here.

---

## ReviewPage — [STUB]

`ReviewPage.jsx` — placeholder, not yet built.

---

## Change Index

| Thing to change | Where |
|---|---|
| Startup data-fetch order | `MainPage.jsx useEffect` |
| Page routes | `App.jsx <Routes>` + `NavBar.jsx NAV_ITEMS` |
| Settings sections / fields | `SettingsPage.jsx SECTIONS` array |
| Chrono result display | `SettingsPage.jsx` chrono status section |
| ReviewPage UI | `ReviewPage.jsx` — currently stub |
