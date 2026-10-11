# Tracks UX rework — plan (A, B, C; D deferred)

Grounding doc for subagents. Do not re-derive scope; if reality contradicts this plan, STOP and report.
Rules binding every agent: laconic; no AskUserQuestion; surface any new module dependency / data move
in 2-4 lines instead of acting; use `linux_scripts/test.sh` (never hand-rolled docker/pytest); work in your
own git worktree; `git add` only relevant files; short imperative commits, no Claude/Co-authored-by; never push.

## Findings (verified 2026-10-11)
- `track_items` = {id, track_id, position, title, note_path, status, completed_at}. One note per item.
- Backend ALREADY supports every attach path — A needs **no new table**:
  - `POST /tracks/{id}/items {title, notePath}` (AddItemRequest) — note attach
  - `captureResource(url, trackId)` (frontend/src/api/capture.js) — URL → ingest → items on the track
  - `POST /capture/file` multipart `file, trackId?, title?, newTrackTitle?, newTrackType?` — file → ingest → items
  - `searchNotes(query)` (api/notes.js) — for a note picker
- Today's UI only exposes: title-only item form + "Add resources" (URL textarea). notePath is never settable by hand.
- Manage track detail (frontend/src/pages/TracksPage.jsx ~L500-920) shows ~10 equal-weight sections.
- Today (`TodayTab` L72) has no reason-per-item; empty state points at the weekly schedule.
- `TodayPlanService.today()` returns `TodayPlan{items, mode, overBudget}`; items carry trackId/title/type.
- Stay in TracksPage.jsx minimally: put new UI in NEW files under `frontend/src/components/organisms/` to avoid merge conflicts
  (all three milestones touch TracksPage.jsx → milestones run **sequentially**, not in parallel).

## Milestones (sequential; one subagent each, own worktree, branch `tracks-ux-mN`)

### M0 — baseline (orchestrator, cheap)
`linux_scripts/test.sh` frontend + tracks tests green on master before branching. Record result.

### M1 — A: one "Add to track" box
- New `TrackAddBox.jsx` replaces the item-title form + "Add resources" in `TrackItemsPanel`.
- One input, three paths, auto-detected:
  1. text that is a URL (one or many lines) → `captureResource(url, trackId)` per URL (reuse existing result list UI)
  2. file dropped on the box / file picker → `POST /capture/file` with `trackId`
  3. any other text → debounced `searchNotes`; dropdown of matches (attach note → `addTrackItem(title, notePath)`), plus final row "Add as plain item '<text>'"
- Items show a type chip: link / note / file / plain (derive from notePath ext & existing item fields). Captured items arrive async:
  show "processing…" chip until notePath appears (poll/refetch `fetchTrackItems` a few times; do not build new infra).
- If chip type can't be derived without a backend field → STOP and report (do not add a column silently).
- Tests: vitest for `classifyInput()` (url / multi-url / text) + component test (note pick adds item with notePath; file drop calls capture/file). 
- UX gate: browse-skill screenshots at desktop + 400px; check 3-second test ("can I tell how to attach a thing?").
- Exit: tests green, FLOWS updated (frontend/FLOWS.md Tracks section), 1 commit.

### M2 — B: one-step create + progressive disclosure
- New `NewTrackForm.jsx`: title (required) + optional paste box (reuses TrackAddBox logic via a shared hook) + optional deadline → creates track and items in one submit.
- Track detail: primary = Items + Deadline. Everything else (Type, Status, Priority, Include in Progress, Weekly schedule, Mini-course, CSV import, Daily capacity) moves under one collapsed "Advanced" disclosure. No feature removed.
- Rename jargon in labels only (API values unchanged): "Mini-course" → "Generate a course", "Include in Progress" → "Show on Progress tab", "Review import" → "Import preview".
- Tests: component test (create with title only; with title+URLs; Advanced collapsed by default). 
- UX gate: screenshots; time-to-first-track ≤ 3 clicks.
- Exit: green, FLOWS updated, 1 commit.

### M3 — C: Today explains itself
- Backend: `TodayPlanService` adds `reason` (String) per item, e.g. "Due Fri · ~3/day to finish" / "Fills leftover capacity" / "Focus mode: most urgent". Pure computation from data it already has; no new storage. Extend `TodayPlanServiceTest`. Also expose `capacity`/`planned` counts for a header ("3 of 4 today").
- Frontend: render reason under each item; header "3 of 4 for today"; real empty state with a single button "Create your first track" (switches to Manage + opens NewTrackForm); rename "Lock-in" label → "Focus on one track" / "Normal" → "Balanced" (label only, API `lockin|normal` unchanged).
- Tests: Java unit (reason strings per branch: deadline, leftover, lockin, overBudget); vitest for empty state CTA.
- Exit: green, FLOWS updated (tracks/FLOWS.md Today Flow + Change Index), 1 commit.

### M4 — integrate (orchestrator)
Merge branches in order M1→M2→M3 (no-ff), run full `linux_scripts/test.sh`, one browse pass over all three tabs
(design-review lens: hierarchy, empty states, 400px). Fix only egregious issues. Remove worktrees AND branches
(`git worktree remove`, `git branch -D`). Then report BUILT vs LIVE; deploy via redeploy only with user's OK.

## Deferred
- D: Feed right-swipe → "add to track" (cross-module: feed → tracks). Needs user go-ahead after M4.
- Defaulting no-deadline tracks to "fill leftover by priority" (drops weekly-schedule requirement): schedule-model change, separate decision.

## Orchestrator checkpoint per milestone
Read agent's report (not the whole diff) → check its test output → `git diff --stat` sanity → merge or send back once. No milestone starts before the previous is merged.
