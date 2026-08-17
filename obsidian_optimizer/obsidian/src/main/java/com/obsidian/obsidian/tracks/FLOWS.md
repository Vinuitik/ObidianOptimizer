# Tracks Domain Flows

Files: TrackRepository.java, TrackController.java, TodayPlanService.java, TrackReviewHandoff.java
Frontend: frontend/src/pages/TracksPage.jsx, frontend/src/api/tracks.js, frontend/src/store/useStore.js (Tracks section)
Plan: /home/victor/.claude/plans/adaptive-finding-bubble.md (Phase 1 of the full Learning Tracks plan)

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

**Phase 1c replaces `TodayPlanService.today()`'s body** with a capacity/deadline/MoSCoW-aware
version (same method signature, same no-persisted-plan principle) — see the plan doc's
Phase 1c section. Two design decisions were made for that upgrade, ahead of writing it:
- **Lock-in mode = single-track focus per day**, not "everything uncapped." Auto-picked (no
  manual picker — picking has a cost) via `urgency = priorityWeight(must=3/should=2/could=1) ×
  pace`, `pace` reusing the deadline-track pace formula already in the plan. No explicit
  recency/rotation state needed: a productive lock-in day burns down `itemsRemaining`, which
  drops that track's urgency below a rival's, so tomorrow's pick rotates on its own.
- **Normal-mode must-overflow**: Normal mode stays capacity-bounded even for `must` tracks
  (pro-rata trim applies if musts alone exceed capacity) — and that trim event is the trigger
  for a "you're behind — switch to Lock-in?" banner/CTA on Today, not a silent drop.

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

## Capture-time track tagging (Phase 1b)

Goal: tag a capture to a track at the moment of capture, on any surface, without slowing
the default "just capture it" path. Three surfaces (extension, mobile PWA, MCP) converge
on two different backend primitives depending on whether the surface goes through the
Java `capture` table at all.

**Deviation from the original plan doc worth flagging**: the plan speculated a bulk
`notes_created` callback on job completion. That callback doesn't exist in this codebase —
standalone notes are published **one at a time**, synchronously, via
`InternalAgentController.createNote()` (`POST /api/internal/notes`), called once per note
from Python `publish.create_note()`. The fan-out below hooks that real per-note callback
instead of inventing a new one.

```
EXTENSION / PWA (capture-queue path):
  POST /api/capture {url|text, trackId? | newTrackTitle?+newTrackType?}
    CaptureController.resolveTrackId(): newTrackTitle set → trackRepo.create() first;
      else the given trackId; neither → null (untagged, unchanged behavior)
    captureRepo.enqueue(...) as always, THEN captureRepo.setTrackId(captureId, trackId)
                                    │
  CaptureIngestWorker.drain() → IngestClient.submit* → embedder ingest job (async, minutes)
                                    │
  embedder synthesis (jobs._synthesize_and_publish[_v2]) calls, per produced note:
    publish.create_note(folder, title, content, capture_id=capture_id)
      → POST /api/internal/notes {folder, name, content, captureId}
        InternalAgentController.createNote():
          write the note as always, THEN linkToTrack(captureId, name, path):
            captureRepo.get(captureId).trackId() != null?
              → trackRepo.addItem(trackId, name, path)   (best-effort, try/catch — a
                track-tagging hiccup must never fail note delivery)

MCP (never touches the Java capture table — see capture/FLOWS.md "MCP ingest durability
gap"; track_id has to ride on the embedder job/tool call itself instead):
  create_track(title, type?)          → POST /api/internal/tracks        → new Track
  ingest_resource(ref, track_id?)     → ingest_jobs.submit(..., track_id=...)
    → job dict carries track_id; jobs._link_track_item() called per note produced
      (parallel to the capture_id-based path above, but self-contained — no Java lookup)
  create_note(text, track_id?, already_processed=True)   → _stage_note_as_is() calls
    publish.add_track_item(track_id, title, path) synchronously right after the write
    (this path is itself synchronous, so no job-dict indirection needed)
```

Both `POST /api/internal/tracks` and `POST /api/internal/tracks/{id}/items` are new
internal-token-gated endpoints on `InternalAgentController` — MCP tool calls never carry
the session cookie the public `/tracks` endpoints require, so track creation/item-append
from a conversation needs its own internal mirror (same pattern as every other
agent-write endpoint in that controller).

**Not implemented** (flagged as extras in the plan doc, not required by its own
verification checklist): the PWA share-target quick-pick ("Add to track" after a
fire-and-forget share) and the InboxReview drag-onto-a-track fallback for trackless
captures. The manual capture form (`CapturePage.jsx`, both Link and Note modes) and the
extension popup both got the full picker; MCP got `create_track` + `track_id` on
`ingest_resource`/`create_note`.

## Settings gate (nav visibility only)

`tracksEnabled` (`app_settings`, default `true` — ships live, not behind a flag) controls
**only** whether the Tracks tab shows in `NavBar` (`tracksOnly` filter, mirrors
`flashcardsOnly`). Turning it off does not stop capture-time track tagging (Phase 1b) or
delete any data — `/tracks` stays reachable directly. Toggle: Settings → Learning Tracks.

## Technology Notes

- **No FK enforcement on `note_path`**: `track_items.note_path` is a soft reference by
  convention, same as `cards.note_path`/`capture.source_path` — nothing rewrites it if the
  note is renamed/moved (`FileRepository.renameNote()`/`moveNote()` don't know about tracks).
  Acceptable for v1: AI-generated/captured items usually sit in `_inbox/` until the user
  files them, and staleness here just means "add to spaced review" silently no-ops (item
  still marked done). Add rename-tracking later if this becomes a real problem.
- **Schema location**: all three tables (`tracks`, `track_items`, `track_schedule`) init in
  `TrackRepository.initSchema()` — single `@PostConstruct`, `CREATE TABLE IF NOT EXISTS` +
  (future) `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for additive migrations, same
  convention as `CardRepository`/`CaptureRepository`. Phase 1c's `daily_capacity` table is a
  separate small addition — same file unless it grows unwieldy (see plan doc, judgment call).
- **`reorderItem` is O(track size)** in the worst case (moving item 1→N shifts every sibling
  by one position). Fine at book/course-chapter scale (tens of items); would need a different
  approach (fractional positions, etc.) if tracks ever held thousands of items — not expected.
- **`capture.track_id` has no FK** (Phase 1b) — same soft-reference convention as
  `note_path`/`source_path`, but here it's load-bearing on startup ordering too:
  `CaptureRepository` and `TrackRepository` are separate `@PostConstruct` beans with no
  guaranteed init order, so a hard FK to `tracks(id)` could fail to create if capture's
  schema ran first. A stale/deleted track id just means `linkToTrack()`'s `trackRepo.get()`
  lookup (inside `addItem`, indirectly) errors and is swallowed — the note itself still
  lands, only the track-item append silently no-ops.
- **Two independent track-tagging paths, deliberately not unified.** The capture-queue
  path (extension/PWA) looks the track up server-side from `capture.track_id`, keyed by
  `captureId`, at note-creation time. The MCP path carries `track_id` directly on the
  embedder job/tool call, because MCP-originated ingest never creates a `capture` row to
  look anything up from. They converge on the same effect (`trackRepo.addItem`) through
  different plumbing — see the Phase 1b flow diagram above.

## Change Index

| Thing to change | Where |
|---|---|
| Track/item/schedule schema | `TrackRepository.initSchema()` |
| Today allocation (Phase 1 flat) | `TodayPlanService.today(LocalDate)` |
| Today allocation (Phase 1c capacity/deadline/MoSCoW) | same method — body replaced, see plan doc |
| Item reorder semantics | `TrackRepository.reorderItem()` |
| Complete-item → FSRS handoff | `TrackReviewHandoff.seedDueToday()` |
| FSRS seed grade for brand-new notes | `TrackReviewHandoff` — `FsrsService.GRADE_GOOD` |
| Nav visibility toggle | `SettingsRepository.isTracksEnabled()` / `NavBar.jsx` `tracksOnly` filter |
| Frontend Tracks state | `useStore.js` — `tracks`, `todayItems`, `trackItems`, `trackSchedules` (wiped on logout via `initialDataState()`) |
| Track item drag-reorder (Manage tab) | `TracksPage.jsx` `TrackItemsEditor` — native HTML5 DnD, position = array index |
| Weekly schedule editor | `TracksPage.jsx` `TrackScheduleEditor` — full-replace PUT, budget='' means "not scheduled" |
| **Phase 1b** — capture.track_id column | `CaptureRepository.initSchema()` / `setTrackId()` |
| **Phase 1b** — resolve trackId or create-on-the-fly | `CaptureController.resolveTrackId()` (used by `capture()` and `captureFile()`) |
| **Phase 1b** — capture-queue note→track fan-out | `InternalAgentController.createNote()` → `linkToTrack()`; Python side threads `capture_id` through `publish.create_note()` (`ingest/jobs.py` `_synthesize_and_publish[_v2]`) |
| **Phase 1b** — MCP track creation/item-append (internal, token-gated) | `InternalAgentController.createTrack()` / `addTrackItem()`; Python `publish.create_track()` / `publish.add_track_item()` |
| **Phase 1b** — MCP tools | `embedder/mcp_tools/write.py` `create_track`, `ingest_resource(track_id=)`, `create_note(track_id=)`; job-side fan-out in `ingest/jobs.py` `_link_track_item()` |
| **Phase 1b** — extension picker | `extension/popup.html` `#cap-track`; `popup.js` `trackSelection()`/`loadTracks()`; `background.js` `listTracks()` + `trackOpts` threaded through `capture()`/`captureText()`/`routeText()`/`capturePage()`/`escalate()` |
| **Phase 1b** — PWA picker | `frontend/src/pwa/CapturePage.jsx` (`trackOpts()`); threaded through `offlineApi.js` `captureUrl`/`captureText` and `outbox.js` `enqueueCapture`/`enqueueCaptureText`/`flush()` for offline replay |
