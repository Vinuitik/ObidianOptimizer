# UI Declutter Plan (2026-10-25)

Source: design audit of live :8081 (screenshots in scratchpad). Goal: an ADHD-friendly app —
one focal point per screen, one accent colour, conventional patterns, no permanent alarms.

## Mental model (confirmed by user)
Three jobs, three places:
- **Sources** = get new information (swipe feed, subscriptions; capture via global button)
- **Learn**   = stage it (first pass over extracted notes before they enter review)
- **Review**  = maintain it (flashcards / reading)
Notes = the vault itself. Everything else is admin → Settings.

## Rules (apply everywhere)
1. **One accent.** `--accent` only for: current selection, the single primary button per view,
   focus ring. Links, inline code, chips, folder markers → neutral (`--text-2`, `--raised`).
2. **No per-item colour.** Drop golden-angle source colours (`sourceColor.js` bands, `srcDot`,
   `--row-src` left borders) in the Learn queue.
3. **No emoji in chrome.** 📁 🚩 📄 → `<Icon>` (folder, flag, file).
4. **Two fonts in UI.** Newsreader = headings + reading prose. Geist = all UI. Geist Mono = real code
   only (remove from chips like "Open ended · 2 pts", "TEXT", "cards", folder input placeholders).
5. **One segmented-control style** (`SegmentedTabs`) and **four button variants**
   (primary / secondary / ghost / danger). Never two tab rows stacked on one screen.
6. **Empty / loading states always resolve** — no skeleton that can spin forever.

## D0 — Foundation (sequential, first)
- Nav: top bar = Review · Sources · Learn · Notes + Capture + avatar menu (Settings, Refresh, Sign out).
  Dashboard, Failures, Get App leave the top bar → links in a new **Settings → System** section
  (routes unchanged). Failures count shows only there; no red badge in the top bar.
  Mobile bottom bar already matches (Review/Sources/Learn/Notes/More) — "More" lists the same.
- Apply rules 3–5 to atoms/molecules (`Chip`, `Button`, `SegmentedTabs`, `Badge`), tokens untouched
  except adding any missing neutral chip token.
- Files: `components/organisms/TopBar*` (or wherever nav lives), `pwa/MobileLayout.jsx`,
  atoms/molecules, `pages/SettingsPage.jsx` (System section only).

## W1 — Learn: note-first, compare is opt-in
- Default view = **the note only** (one column, readable width). No source panel for text sources —
  the source IS the note text, so showing both is a duplicate.
- For **video / youtube / pdf / web** sources: a **"Compare with source"** toggle in the note header.
  On: side-by-side (desktop, existing `LearnLayout`) or Source|Note switch (mobile). Off by default;
  last choice remembered in localStorage. Text sources never show the toggle; RSVP moves to a
  small "Speed read" action on the note, not a panel.
- Queue (item list) becomes a collapsible drawer on the left, closed by default once an item is open;
  grouped by source with plain header + count, no colours, no 📁 chips.
- Action bar: one primary (Save & file / Save & acknowledge), folder picker as secondary,
  Split + Discard in a "⋯" overflow menu. Keyboard shortcuts unchanged.
- Today/Week tabs stay (one row). Today = Inbox, then scheduled track items below.
- Files: `organisms/InboxReview.jsx(.module.css)`, `SourceSplicePanel.jsx`, `LearnLayout`, `pages/LearnPage.jsx`.

## W2 — Sources: one row, real empty state
- Single tab row: **Feed · Subscriptions**. Capture tab removed (global Capture button does it).
- Investigate why the feed shows a skeleton indefinitely on live (likely load never resolves or
  errors silently) → fix; empty = "Nothing new" + "Manage subscriptions" link.
- Swipe card: one primary (Accept), Bin as secondary, Undo ghost — as today, restyled to rules.
- Files: `pages/SourcesPage.jsx`, `organisms/FeedView.jsx`, `FeedSources.jsx`.

## W3 — Settings: calm list, not a card stack
- Sections separated by headings + dividers, not boxed cards. Desktop: sticky section index on the
  left (Vaults, Learning, Feed, Sync, Bin, System, Advanced).
- New **System** section (from D0): Dashboard, Pipeline failures (count), Get the app.
- Buttons normalised to the four variants.
- Files: `pages/SettingsPage.jsx(.module.css)`, `VaultsPanel.jsx`, `BinPanel.jsx`.

## W4 — Review + Notes polish
- Review list: drop the repeated "cards" chip; show only a small icon for *read* items (the minority).
- Flag button: icon, not emoji. Answer box chip in Geist, not mono.
- Notes empty state: icon instead of 📄; one "New note" primary, "New folder" secondary.
- Files: `pages/ReviewPage.jsx(.module.css)`, `components/templates/SplitLayout.jsx` (empty state only).

## W5 — Learn Today is scheduled by tracks (backend + Learn data)
Decision (user, option A): Learn → Today shows only what tracks schedule for today; the rest waits
in "Backlog (N)" (collapsed, still browsable/fileable).
- **Unit of learning = one inbox group**, never split across days. Group key already exists in
  `InboxController`: `<captureId>[/<chapter>]` → a video/article = one unit; a book = one unit
  per chapter. Budget (`track_schedule.daily_item_budget`) counts units; a unit is taken whole
  even if large.
- **Every unit belongs to a track.** New: a default **"General"** track (auto-created, budget 1/day,
  editable in Week). Assignment on ingest: subscription source → that subscription's track;
  everything else (manual capture, share target, extension, MCP) → General.
- **Backfill:** on startup migration, every existing inbox group without a track is assigned
  (subscription track if its feed item matches, else General), so old items get scheduled too.
- **Re-home anytime:** in the Learn note header, a "Track: General ▾" quick selector moves the
  whole unit. Capture modal gets the same optional selector; MCP `ingest_resource`/`create_note`
  get an optional `track` param (name; unknown → General).
- **Schema:** `track_item_groups` gets a `chapter TEXT` column and the unique key becomes
  (track_id, capture_id, chapter) — needed for per-chapter units.
- **Today picker (backend):** for each track scheduled today, take its next N unfinished units
  in capture order; a unit started yesterday but not finished stays on Today (no drop-off).
  Endpoint: extend the existing today-plan API with `units[]` (group key, title, note count, track).
- Frontend: W1's queue drawer shows Today's units; "Backlog (N)" section below, collapsed.
- Files: `tracks/*` (repo/service/controller), `inbox/InboxController.java`, ingest assign hook,
  MCP tool schema, `organisms/InboxReview.jsx` data source, `TodayPlan.jsx`.
- W5 backend lands before W1's queue wiring; W1 builds the UI against W5's response shape.

## F — Final
- Re-run screenshots desktop + 390px for all pages; colour count per page ≤ ~18 (Learn was 42).
- `./linux_scripts/test.sh` green. FLOWS.md updated in touched folders.

## Order
D0 ‖ W5-backend → W1 (+W5 UI) ‖ W2 ‖ W3 ‖ W4 (Sonnet agents, worktrees, disjoint files) → F.
W3 depends on D0's System section stub; W1–W4 must not edit atoms (D0 owns them).
