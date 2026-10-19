# Learn / Sources restructure — plan

Status: APPROVED 2026-10-19. Problem: Tracks was "a list of notes with no scheduling" — the Today
allocator skips tracks with no weekday schedule and no deadline, and the plan lived on an isolated
/tracks page Learn never showed.

## Target IA
- **Sources** (new page, data intake): Feed swipe, add subscriptions, add resources (URL/file capture).
  PWA: quick idea capture lives here too (open question P1).
- **Learn** = two views:
  - **Today**: today's scheduled track items (from `/tracks/today`) + Inbox (InboxReview) below, one screen.
  - **Week**: Mon–Sun grid of which self-assigned courses (tracks) happen which day; it IS the track
    editor — click a track → side panel (items, deadline, priority, schedule, capacity, mode).
- **/tracks page removed** (route + nav). Its ManageTab/TodayTab pieces move into Learn.
- **Review** page unchanged.

## Backend changes (tracks/)
- TodayPlanService: tracks with no schedule entry AND no deadline fill leftover daily capacity in
  priority order (must>should>could), instead of being skipped.
- New `GET /api/tracks/week` → 7 days projected with the same allocator (assume no completions),
  `{weekday, capacity, items:[{trackId,trackTitle,count}]}` — Week grid reads this.
- Drop capture-time track tagging (Phase 1b): stop setting `capture.track_id`, remove trackPending UI;
  leave the DB column (harmless).

## Milestones
- L1 backend: fallback allocation + /tracks/week + tests; drop Phase 1b tagging (backend side).
- L2 frontend: Sources page (Feed + subscriptions + resource capture, PWA capture) — after P1 answered.
- L3 frontend: Learn Today|Week, Week editor panel, delete /tracks, nav, drop trackPending. Depends on L1.
- Update tracks/FLOWS.md + frontend FLOWS after each.
