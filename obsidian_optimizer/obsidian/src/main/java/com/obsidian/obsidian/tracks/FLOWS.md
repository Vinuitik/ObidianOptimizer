# Tracks Domain Flows

Files: TrackRepository.java, TrackController.java, TodayPlanService.java, TrackProgressService.java, TrackReviewHandoff.java
Tests: TrackRepositoryIT.java, TodayPlanServiceTest.java, TrackProgressServiceTest.java, TrackReviewHandoffTest.java
Frontend: frontend/src/pages/TracksPage.jsx, frontend/src/api/tracks.js, frontend/src/store/useStore.js (Tracks section)
Plan: /home/victor/.claude/plans/adaptive-finding-bubble.md (Phase 1 of the full Learning Tracks plan)
Sub-flow: [FLOWS_feed.md](../feed/FLOWS_feed.md) — Passive Feed design/plan (P0–P4 phasing). Backend (P1a) is
built — see [feed/FLOWS.md](../feed/FLOWS.md) for the real flows. Subscriptions moved out of `tracks/`
entirely (see "Subscriptions — moved" below); `TrackAgentClient.java` (discover client) stays here,
shared by `feed/`.

---

## What this is

Learning Tracks = structured curricula (a book, a course, an article series) with an
ordered item list and a days-of-week schedule, so a "Today" view shows what's planned.
**Deliberately NOT FSRS-governed** — FSRS/bandit scheduling (see `cards/FLOWS.md`) is a
separate post-completion retention system. The only bridge between the two: completing a
track item can optionally hand its note off into the FSRS pool (`TrackReviewHandoff`).

## CRUD Flow

```
POST   /api/tracks {title, type}              → TrackRepository.create() (source='manual')
PATCH  /api/tracks/{id} {title?, type?, status?, deadline?, priority?, includeInProgress?, clearDeadline?}
DELETE /api/tracks/{id}                        → ON DELETE CASCADE takes items + schedule with it

POST   /api/tracks/{id}/items {title, notePath?}   → appended at next free position (MAX(position)+1)
PATCH  /api/tracks/items/{itemId} {title?, position?}   → title edit and/or reorder in one call
DELETE /api/tracks/items/{itemId}
POST   /api/tracks/items/{itemId}/complete {addToReview}
    → TrackRepository.completeItem() (status='pending'→'done', idempotent — no-op if already done)
    → if addToReview && item has note_path: TrackReviewHandoff.seedDueToday(notePath)

GET/PUT /api/tracks/{id}/schedule              → weekday(0=Mon..6=Sun) → daily_item_budget map
    PUT is a FULL REPLACE (weekly editor always submits the complete Mon..Sun state)
```

Reorder (`TrackRepository.reorderItem`): position-swap shifting only the siblings between
old and new position — same shape as any ordered-list update, no full renumber pass.
`position` values stay contiguous 0..n-1 per track, which is what lets the flat Today
allocator (`nextPendingItems`) just `ORDER BY position LIMIT budget`.

## Today Flow (Phase 1 — flat, read-time only)

```
GET /api/tracks/today → TodayPlanService.today():
  weekday = today's DayOfWeek (0=Mon..6=Sun)
  for each active track:
    budget = track_schedule[track][weekday]     skip track entirely if no entry (or budget<=0)
    take next `budget` PENDING items ordered by position
  flatten into one list, each item annotated with {trackId, trackTitle, trackType}
```

**No persisted daily-plan table, no `@Scheduled` job.** An unfinished item just stays
`pending` and resurfaces tomorrow — carryover is emergent, not stored. Same principle
`pwa/reviewPlan.js`'s `allocateTracks()` already uses for the FSRS hybrid split (different
domain, same "don't persist what you can recompute" idea).

## Today Flow (Phase 1c — capacity/deadline/MoSCoW, IMPLEMENTED)

`TodayPlanService.today(LocalDate)`'s body was replaced with the capacity-weighted version
below (same method signature and no-persisted-plan principle as Phase 1 — everything here
is recomputed fresh on every `GET /tracks/today`, nothing cached or stored). Returns
`TodayPlan{items, mode, overBudget}` instead of a bare list.

```
mode = settingsRepo.getOrDefault("trackMode", "normal")   // app_settings, same EAV as everything else
todayCap = trackRepo.getCapacity(weekday)                 // daily_capacity table, ignored in lock-in

split active tracks into deadlineTracks (deadline != null) / manualTracks (deadline == null)
for each deadlineTrack with pending items:
  itemsRemaining = countPendingItems(track)
  capSpan = Σ daily_capacity(d) for d in [today, deadline)     // the pace denominator
  pace = capSpan > 0 ? itemsRemaining / capSpan : itemsRemaining  // deadline today/past -> wants it ALL now

if mode == lockin and deadlineTracks not empty:
  chosen = argmax(priorityWeight(must=3/should=2/could=1) * pace)   // single-track focus
  items = ALL of chosen's remaining pending items, uncapped by todayCap
  // no manual tracks mixed in — the whole point is context-switch reduction
else:  // normal mode, or lock-in with nothing on a deadline (falls back to normal's shape)
  reserved[track] = pace[track] * todayCap
  if Σreserved > todayCap:
      trim could first, then should; if musts ALONE still exceed todayCap,
      pro-rata scale the musts too -> overBudget = true (never a silent drop)
  take reserved[track] (rounded, capped at itemsRemaining) from each deadline track
  remainingCap = todayCap - Σtaken
  manual tracks WITH a track_schedule entry for today: take min(budget, pending, remainingCap)
  manual tracks with NO schedule entry for today (L1 fallback): greedily soak up what's left,
      sorted must>should>could (null = should), ties keep listActive() order (created_at DESC);
      each takes min(pending, remainingCap) -> never exceeds todayCap
```
The whole thing lives in the pure `TodayPlanService.allocate(date, mode, cap, capacityOf, active,
pending, scheduleBudget)` (no repo access, no mutation) — Today and Week both call it.
To change fallback order: `TodayPlanService.allocate()` (the `unscheduled.sort`).

Two design decisions were resolved before implementing this (see
`learning-tracks-phase1-2026-08-16.md` memory for the reasoning):
- **Lock-in mode = single-track focus per day**, not "everything uncapped." No explicit
  recency/rotation state: a productive lock-in day burns down `itemsRemaining`, dropping
  that track's urgency below a rival's, so tomorrow's pick rotates on its own.
- **Normal-mode must-overflow**: Normal mode stays capacity-bounded even for `must` tracks
  — that pro-rata-trim event is exactly what `overBudget=true` signals, driving a
  "you're behind — switch to Lock-in?" banner/CTA on the Today tab (`TracksPage.jsx`
  `TodayTab`'s `overBudgetBanner`), not a silent drop.

`daily_capacity(weekday, capacity)` seeds Mon-Fri=4/Sat-Sun=6 on first boot
(`TrackRepository.seedDefaultCapacity()`, `ON CONFLICT DO NOTHING` so a user edit survives
restarts) — editable per-weekday via `GET/PUT /tracks/capacity` (`setCapacity` is a
**partial** upsert, unlike schedule's full-replace). Mode toggle: `PUT /tracks/mode
{mode}` → `TodayPlanService.setMode()` → `settingsRepo.set("trackMode", ...)`. Both
surfaced directly on `TracksPage`: the mode toggle + overflow banner on the Today tab,
a collapsible "Daily capacity" 7-input panel at the top of the Manage tab's track sidebar
(`CapacityPanel`) — tracks-specific, so it lives here rather than generic Settings.


### Today reasons (M3, computed, not stored)
`TodayItem.reason` + `TodayPlan.planned`/`capacity` (additive JSON). Per track, same branch that chose the items:
deadline → `Due Fri · ~N/day to finish` (`Due today`/`Overdue` when deadline <= today); trimmed musts →
`Due Fri · trimmed: must deadlines exceed today's capacity`; manual → `Fills leftover capacity`;
lock-in → `Focus: most urgent deadline · Due Fri`. `planned` = items returned, `capacity` = today's `daily_capacity`.
UI labels only: lockin = "Focus on one track", normal = "Balanced" (API values unchanged).

## Progress Flow (Phase 1d, IMPLEMENTED)

```
GET /tracks/progress → TrackProgressService.progress():
  rows = trackRepo.listProgressRows()   -- one SQL query, GROUP BY t.id:
    SELECT t.*, COUNT(ti.id) total, COUNT(ti.id) FILTER (done) done
    FROM tracks t LEFT JOIN track_items ti ON ti.track_id = t.id
    WHERE t.include_in_progress = true AND t.status = 'active'

  for each row:
    if track.deadline == null: onTrack = null   -- nothing to pace against
    else:
      created = track.createdAt as LocalDate (system zone)
      capacitySpan    = trackRepo.capacityBetween(created, deadline)
      capacityElapsed = trackRepo.capacityBetween(created, today)
      expectedDone = capacitySpan > 0 ? itemsTotal * (capacityElapsed/capacitySpan) : itemsTotal
      onTrack = itemsDone >= expectedDone
```

Response shape: `{id, title, type, itemsDone, itemsTotal, deadline?, onTrack?}[]`. `onTrack` is
`null` (not `false`) for non-deadline tracks — the frontend only renders the on-track/behind
badge when `deadline` is set. `capacityBetween` is the same pace denominator Phase 1c's
`TodayPlanService` uses, promoted to a **public method on `TrackRepository`** (rather than
private in each service) since it only depends on the capacity table — but `TodayPlanService`
keeps its own private copy rather than switching to call `TrackRepository.capacityBetween()`:
that service is unit-tested with a fully-mocked `TrackRepository`, and routing through a mocked
method would silently return 0.0 instead of running the real summation the existing tests stub
via `getCapacity()`. Two copies of a 6-line loop, not shared, to avoid that breakage — see
Technology Notes.

Frontend: `TracksPage.jsx` `ProgressTab` — a card grid (`.progressGrid`), each card reusing the
existing `Ring` atom (`components/atoms/Ring.jsx`, previously unused elsewhere in the app) for
the completion percentage, plus an on-track/behind badge (`.onTrackBadge`/`.behindBadge`) for
deadline tracks only.

## FSRS Handoff Flow

```
TrackReviewHandoff.seedDueToday(notePath):
  1. stateWriter.normalizeLegacy(notePath)   — migrate real Obsidian-SR frontmatter if present
     → migrated non-null: reschedule(notePath, today), done (memory state preserved)
  2. stateWriter.read(notePath)              — already in the FSRS pool (from a prior handoff
     or a normal review)?
     → non-null: reschedule(notePath, today), done (memory state preserved)
  3. brand new note, nothing to preserve: fsrs.initialState(GRADE_GOOD) seeded,
     stateWriter.writeState(notePath, state, now, today, 1)
```

Reuses `FsrsStateWriter` exactly as `ReviewPreparationService.prepare()` does for legacy
migration — **no new scheduling math**. The note just flips into the pool the existing
nightly `BankruptcyService`/`SpreadService`/`CardJobWorker` and `GET /reviews/due` already
watch. `FsrsStateWriter.mirror()` no-ops silently if the note has no frontmatter block yet
(common for a fresh AI-generated/captured note) — the DB row (`note_reviews`) is what
`/reviews/due` actually queries, so the note surfaces for review regardless.

### Week Flow (L1 + W1 done/planned, IMPLEMENTED)
```
GET /api/tracks/week[?start=YYYY-MM-DD] -> TrackController.week() -> TodayPlanService.week(start, today)
  done[day][track] = TrackRepository.completionsByDay(vault, start, min(start+7, today+1), systemZone)
                     (COUNT of track_items done, grouped by completed_at local date)
  pending[track]   = countPendingItems for every active track (working copy)
  for i in 0..6: d = start+i
      d <  today : items = actual completions only; planned = done          (no re-projection)
      d == today : a = allocate(...) on remaining pending; planned = done + a.take; done = completions so far
      d >  today : a = allocate(...); planned = a.take; done = 0; pending[track] -= a.take
  -> [{date, weekday, capacity, done, planned,
       items:[{trackId, trackTitle, trackType, count(==planned), done, planned}]}]
```
To change the done/planned rules: `TodayPlanService.week(LocalDate, LocalDate)`; the completion query:
`TrackRepository.completionsByDay()`. Timezone = JVM default (`ZoneId.systemDefault()`), same as `LocalDate.now()`.
Frontend: `api/tracks.js fetchWeekPlan()` (badges are a later frontend milestone). `start` earlier than today
shows past days as actuals; omit it for today..today+6 (no past days).

## I1 - tracks group EXISTING notes only (IMPLEMENTED)

Product model: all raw-data intake is Sources -> capture -> ingest -> Inbox. A track never ingests
anything; it groups notes that already exist and schedules them. Minicourse is kept.

```
Add to a track:   POST /api/tracks/{id}/items {title, notePath}   TrackController.addItem()
                    notePath REQUIRED (400 without) -> trackRepo.addItem(id, title, notePath)
                  To change: TrackController.addItem()
Create a track:   POST /api/tracks {title, type?}                  TrackController.create()
                    sourceUrl/sourceType no longer accepted (old Subscribe redirect to feed/ is gone;
                    subscribe via POST /api/feed/sources)
MCP:              create_track(title, type?) -> POST /api/internal/tracks (empty track, groups existing notes)
                  ingest_resource(ref) / create_note(text, ...) have NO track_id param; notes land in the Inbox
Feed accept:      FeedService.accept() -> captureRepo.enqueue -> Inbox   (no track, no "Passive" track)
Capture:          capture.track_id never read/written (column kept); InternalAgentController.createNote()
                  ignores captureId for track purposes; POST /api/internal/tracks/{id}/items DELETED
Minicourse:       GET /api/internal/tracks/{id}/items -> embedder minicourse.build_course_bundle() reads each
                  item's notePath off the vault (items without notePath skipped) - works unchanged
```
Removed: `POST /api/internal/tracks/{id}/items`, `TrackController` sourceUrl redirect,
`CaptureRepository.setTrackId()`, `Capture.trackId`, `InternalAgentController.linkToTrack()`,
`SourceRepository.Source.targetTrackId` (+ create/update params), embedder `publish.add_track_item`,
`jobs._link_track_item`, MCP `track_id` params, extension `listTracks`/`trackOpts`.
Left (unused, harmless): `TrackRepository.getOrCreatePassive()/getOrCreateGroup()`, the
`capture.track_id`, `sources.target_track_id`, `track_item_groups` columns/tables, existing "Passive" track rows.
[NOT IMPLEMENTED] Excel import (`POST /tracks/import/commit`) still creates items with `notePath = null`
(titles only) - kept for now, flagged as an open product decision.

## Settings gate (nav visibility only)

`tracksEnabled` (`app_settings`, default `true` — ships live, not behind a flag) controls
**only** whether the Tracks tab shows in `NavBar` (`tracksOnly` filter, mirrors
`flashcardsOnly`). Turning it off does not delete any data — `/tracks` stays reachable directly. Toggle: Settings → Learning Tracks.

## Technology Notes

- **No FK enforcement on `note_path`**: `track_items.note_path` is a soft reference by
  convention, same as `cards.note_path`/`capture.source_path` — nothing rewrites it if the
  note is renamed/moved (`FileRepository.renameNote()`/`moveNote()` don't know about tracks).
  Acceptable for v1: AI-generated/captured items usually sit in `_inbox/` until the user
  files them, and staleness here just means "add to spaced review" silently no-ops (item
  still marked done). Add rename-tracking later if this becomes a real problem.
- **Schema location**: all four tables (`tracks`, `track_items`, `track_schedule`,
  `daily_capacity`) init in `TrackRepository.initSchema()` — single `@PostConstruct`,
  `CREATE TABLE IF NOT EXISTS` + (future) `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for
  additive migrations, same convention as `CardRepository`/`CaptureRepository`. Kept in the
  same file rather than a dedicated `CapacityRepository` — small enough not to warrant the
  split; revisit if it grows.
- **`daily_capacity` seeding is `ON CONFLICT DO NOTHING`, not an upsert-to-default.** A
  user's hand-tuned weekday value survives every restart; the seed only ever fills gaps
  (e.g. a genuinely fresh install, or if a row were somehow deleted).
- **Phase 1c's numbers are `double`, not integer item counts.** `pace`/`reserved` are
  fractional (e.g. "1.67 items today"); `Math.round()` happens only at the very end, right
  before calling `nextPendingItems(track, take)`. Rounding earlier would compound error
  across the could→should→must trim cascade in `trimByPriority()`.
- **`reorderItem` is O(track size)** in the worst case (moving item 1→N shifts every sibling
  by one position). Fine at book/course-chapter scale (tens of items); would need a different
  approach (fractional positions, etc.) if tracks ever held thousands of items — not expected.
- **`capture.track_id` / `sources.target_track_id` are dead columns** (I1): kept (no destructive
  migration), nothing reads or writes them. Old extension/PWA builds may still send
  `trackId`/`newTrackTitle` on `/capture`; the backend ignores those fields.
- **Items need a `notePath` but it is a soft reference**: `addItem` checks non-blank, NOT that the
  file exists (the UI picks from the vault; MCP/curl could pass a typo). Minicourse skips unreadable notes.
- **`TrackRepository.capacityBetween()` has two callers, deliberately not consolidated
  into one.** `TrackProgressService` calls the real `TrackRepository.capacityBetween()`
  method directly (its Mockito test stubs that method's return value). `TodayPlanService`
  keeps a private copy of the same 6-line loop over `getCapacity()`, because
  `TodayPlanServiceTest` mocks `TrackRepository` entirely and stubs `getCapacity(weekday)`
  per-call to assert on the pace math — routing that service through
  `trackRepo.capacityBetween(...)` would make the mock return 0.0 (Mockito's default for
  an unstubbed method) instead of running the real summation, silently breaking every
  existing Phase 1c test. Small, deliberate duplication over a shared call that would have
  required rewriting already-passing tests for no behavior change.
- **Subscription/feed poll-interval default is seed-once**: `insertDefault("subscriptionsPollIntervalMs")`
  is `ON CONFLICT DO NOTHING`, so changing the code default doesn't touch an existing install's row;
  not exposed in `GET/PUT /settings`. See feed/FLOWS.md for the rest of the poll/dedup/auto-accept
  behavior (that implementation superseded the one originally documented in this section).

## Change Index

| Thing to change | Where |
|---|---|
| Track/item/schedule schema | `TrackRepository.initSchema()` |
| Today allocation (Phase 1 flat, superseded) | `TodayPlanService.today(LocalDate)` |
| **Today allocation (Phase 1c capacity/deadline/MoSCoW, live)** | same method — see "Today Flow (Phase 1c)" above |
| **Daily capacity table + seed** | `TrackRepository.initSchema()` / `seedDefaultCapacity()` (Mon-Fri=4, Sat-Sun=6) |
| **Capacity CRUD (partial upsert)** | `TrackRepository.getCapacity()`/`getCapacityMap()`/`setCapacity()`; `GET`/`PUT /tracks/capacity` |
| **Normal vs Lock-in mode** | `TodayPlanService.getMode()`/`setMode()` (`app_settings` key `trackMode`); `PUT /tracks/mode` |
| **Lock-in track picker (urgency)** | `TodayPlanService.pickLockInTrack()` / `priorityWeight()` |
| **MoSCoW trim (could→should→must pro-rata)** | `TodayPlanService.trimByPriority()` — return value is the `overBudget` signal |
| **Deadline-track pace** | `TodayPlanService.capacityBetween()` (Σ daily_capacity over `[today, deadline)`) |
| Today reason strings / planned / capacity | `TodayPlanService.today()` (`dueLabel()`, reason args to `toTodayItems()`) |
| Mode toggle + overflow banner UI | `TracksPage.jsx` `TodayTab` (`modeRow`, `overBudgetBanner`) |
| Capacity settings UI | `TracksPage.jsx` `CapacityPanel` (Manage tab sidebar, collapsible) |
| Item reorder semantics | `TrackRepository.reorderItem()` |
| Complete-item → FSRS handoff | `TrackReviewHandoff.seedDueToday()` |
| FSRS seed grade for brand-new notes | `TrackReviewHandoff` — `FsrsService.GRADE_GOOD` |
| Nav visibility toggle | `SettingsRepository.isTracksEnabled()` / `NavBar.jsx` `tracksOnly` filter |
| Frontend Tracks state | `useStore.js` — `tracks`, `todayItems`, `trackItems`, `trackSchedules` (wiped on logout via `initialDataState()`) |
| Track item drag-reorder (Manage tab) | `TracksPage.jsx` `TrackItemsEditor` — native HTML5 DnD, position = array index |
| Unscheduled-track fallback order / capacity math (Today+Week) | `TodayPlanService.allocate()` |
| 7-day projection + done/planned badges (W1) | `TodayPlanService.week()` / `week(start, today)` / `TrackRepository.completionsByDay()`; `GET /api/tracks/week` -> `TrackController.week()`; `api/tracks.js fetchWeekPlan()` |
| Weekly schedule editor | `TracksPage.jsx` `TrackScheduleEditor` — full-replace PUT, budget='' means "not scheduled" |
| capture.track_id column (dead since I1) | `CaptureRepository.initSchema()` |
| **Phase 1d** — Progress query (SQL join + counts) | `TrackRepository.listProgressRows()` |
| **Phase 1d** — onTrack calc | `TrackProgressService.progress(LocalDate)` / `toProgress()` |
| **Phase 1d** — shared capacity-window sum | `TrackRepository.capacityBetween()` (public; `TodayPlanService` keeps its own private copy — see Technology Notes) |
| **Phase 1d** — Progress tab UI | `TracksPage.jsx` `ProgressTab`; card styles in `TracksPage.module.css` `.progressGrid`/`.progressCard*` |
| Subscriptions (now "Passive Feed") — everything poll/accept/bin/restore/dedup | **[feed/FLOWS.md](../feed/FLOWS.md)** |
| Legacy Subscribe-button redirect (`sourceUrl` on `POST /tracks`) | [REMOVED in I1] `TrackController.create()` |
| Require notePath on track items (I1) | `TrackController.addItem()` |
| MCP track creation (internal) | `InternalAgentController.createTrack()`; `publish.create_track()`; `mcp_tools/write.py create_track` |
| Discover HTTP client (shared with `feed/`) | `TrackAgentClient.discover()` / `Candidate` |
| Vault scoping (M3) | `TrackRepository` methods take `long vaultId` first; item routes vault-check via `TrackRepository.getItem(vaultId, id)` (items inherit their vault through `tracks`); `TodayPlanService`/`TrackProgressService`/`TrackController` use `activeVault.id()` |
