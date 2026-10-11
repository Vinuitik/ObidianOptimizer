# UI Redesign — "Obsidian Glass" (identity + centralised styling + flow refactor)

Grounding doc for every subagent. Do not re-derive scope or re-litigate decisions below; if reality
contradicts this plan, STOP and report (2-4 lines). Owner/orchestrator: main session (Opus). Implementers:
Sonnet subagents, one per milestone, own git worktree.

Agent rules (binding): laconic; no AskUserQuestion; surface any new module dependency / data move instead of
acting; tests ONLY via `linux_scripts/test.sh frontend` (add `java-unit` if you touch Java); `git add` only
your files, never -A; short imperative commit msg, NO Claude / Co-authored-by line; never push; never
--no-verify; never run deploy scripts. FIRST STEP in a worktree: `git merge master` and confirm the latest
"UI:" commit is present (worktrees may start from a stale base). Worktree has no node_modules: copy
`/home/victor/Desktop/ObidianOptimizer/frontend/node_modules` in (untracked) if test.sh needs it.
NOTE: every commit (worktrees too) fires the post-commit hook = `systemctl restart` of the live stack. That is
expected; do not try to disable it.

---

## 1. Where we are (audit 2026-10-11, ratings laptop/mobile)
Dashboard 8/6 · Notes 7/6 · Failures 7/5 · Get App 7/7 · Review 6/7 · Settings 6/6 · Learn 5/5 · Tracks 5/3 ·
Extension 3-6 · PWA 5-6. Verdict: "meh" — competent dark UI, no identity, inconsistent controls.

Root causes (verified in code):
- `styles/tokens.css` exists but pages bypass it: 134 hard-coded colours across 19 `*.module.css`; buttons
  re-implemented per page (`primaryBtn`, `secondaryBtn`, `saveBtn`, `doneBtn`, `newBtn`… each ×3).
- NO base styling for form elements and no `color-scheme: dark` → any raw `<input>/<select>/<textarea>/
  type=date` renders as a white browser widget (the Tracks "Manage" form regression, extension Subscribe form).
- `atoms/Button` exists but only 11 files use it; no Input/Select/Field/Card/EmptyState/ErrorState primitives.
- 7 overlapping radius tokens, 3 vague easing tokens, muted text `#5c5670` ≈3:1 contrast (fails AA).
- Fonts Space Grotesk + DM Sans = the default "AI-generated dashboard" pairing; zero personality.
- Extension `popup.css` carries a hand-copied token block (drifts).

## 2. Styling centralisation — options and decision
1. Tailwind — rewrite ~6.4k lines of CSS + new build dep + new mental model. Rejected: biggest migration, no
   payoff over tokens for a single-dev app.
2. CSS-in-JS / vanilla-extract — new dep + build change. Rejected: same benefits as 4 with more machinery.
3. Component kit (Radix/shadcn) — shadcn assumes Tailwind; Radix only for complex widgets. Not now.
4. **CHOSEN: tokens → base layer → primitive components, CSS Modules for layout only.** Zero new deps,
   incremental, matches the existing atoms/molecules structure, reversible.

Layering (each layer may only use the ones above it):
```
styles/tokens.css   primitive palette + SEMANTIC tokens (only place a hex/rgba may live)
styles/base.css     element defaults: button, input, select, textarea, date, :focus-visible, a, h1-h4,
                    ::selection, scrollbars, color-scheme:dark, prefers-reduced-motion
atoms/ molecules/   primitives (below) — the only place control *appearance* is defined
pages/organisms     *.module.css = LAYOUT ONLY (grid/flex/gap/size); colours via var(--…) only
```
Enforcement: `src/__tests__/styleGuard.test.js` fails if any `*.module.css` outside `styles/` contains a hex /
rgb(a) / hsl literal or a font-family (allowlist file for justified exceptions, expected ~0). Pages migrate
off the allowlist milestone by milestone; the allowlist must be EMPTY at the end.

Primitives (F0 builds them; pages must use them, not restyle native elements):
- atoms: `Button` (variant primary|secondary|ghost|danger, size sm|md, `loading`, `icon`; upgrade the existing
  atoms/Button in place, keep its import path), `IconButton` (aria-label required), `Input`, `Textarea`,
  `Select`, `Checkbox`, `Badge` (neutral|accent|ember|success|danger), `Skeleton` (lines/rect), `Spinner`.
- molecules: `Field` (label + hint + error, wires `id`/`aria-describedby`), `Card` (+ `Card.Header`),
  `Disclosure` (animated, chevron, remembers open state per key in localStorage), `SegmentedTabs` (keyboard,
  44px mobile), `EmptyState` (icon, title, one-line why, ONE primary action), `ErrorState` (human message +
  Retry; never a bare status code), `PageHeader` (title, subtitle, right-side actions), `ListRow`.
- motion: `motion/presets.js` (framer-motion variants: `fadeRise`, `listItem`, `collapse`, `swipe`) +
  `PageTransition` wrapper used by both routers. Reduced-motion → opacity-only.
- `/styleguide` route (logged-in, not in nav) rendering every primitive in every state — the visual test bed.

## 3. Brand identity — "Obsidian Glass"
Idea: a quiet scholar's study cut from volcanic glass. Near-black with a faint violet cast, one amethyst glint
for "act here", ember for "now / due / streak". Serif for thinking (headings, reading), clean grotesk for doing
(controls). Calm, dense, fast; the content is the hero, chrome recedes.

Typography (Google Fonts, `display=swap`, system fallbacks):
- `--font-display` / `--font-reading`: **Newsreader** (opsz) — page titles, card titles, note bodies,
  flashcard prompts. Reading size 18px/1.65.
- `--font-ui`: **Geist** — all controls, nav, labels, body UI text. 14px base, 13px dense.
- `--font-mono`: **Geist Mono** — counts, timestamps, code, the small UPPERCASE eyebrow labels (keep this
  signature but max one eyebrow per panel).
- Scale: 12 / 13 / 14 / 16 / 18 / 22 / 28 / 36. Headings weight 500-600, tracking -0.01em; UI 400/500/600.

Colour (semantic names are what components use):
- Ground: `--bg` #0A0A0F · `--surface` #111118 · `--raised` #181822 · `--overlay` #20202C
- Text: `--text` #EDEBF5 · `--text-2` #A6A1BA · `--text-3` (muted) #7A7493 (≥4.5:1 on --bg)
- Lines: `--line` rgba(255,255,255,.07) · `--line-strong` rgba(255,255,255,.13)
- Amethyst (brand/action): `--accent` #8B6CFF · `--accent-hover` #9D82FF · `--accent-text` #B9A8FF ·
  `--accent-tint` rgba(139,108,255,.14) · `--accent-line` rgba(139,108,255,.38)
- Ember (now/due/streak/warning): `--ember` #F2A65A · `--ember-tint` rgba(242,166,90,.14)
- `--success` #4CC38A · `--danger` #F06A6A (+ `-tint` variants)
- Focus: `--focus-ring` 0 0 0 2px var(--bg), 0 0 0 4px var(--accent-text)
- Signature "glass" surface: cards get `box-shadow: inset 0 1px 0 rgba(255,255,255,.04), var(--shadow-1)` —
  a 1px top sheen like polished obsidian. Never gradients behind text, never glow except focus.
- Primary button = solid `--accent` with white text (today it is a dim tint — reads as disabled).
- Keep legacy token names as aliases in tokens.css until the last page migrates, then delete them (M-final).

Shape & space: radii `--r-sm` 6 · `--r-md` 10 · `--r-lg` 14 · `--r-pill` 999 (replace the 7 old ones).
Spacing keeps the 4px scale `--sp-1..10`. Controls: 36px desktop, 44px at ≤768px. Content max-width 1200px;
reading column 68ch.

Motion (tokens): `--dur-1` 120ms (press/hover) · `--dur-2` 200ms (enter/exit) · `--dur-3` 320ms (panels/pages) ·
`--ease-out` cubic-bezier(.2,.8,.2,1) · `--ease-in-out` cubic-bezier(.4,0,.2,1).
Rules: every state change is animated or instant — never a blank flash. Completed/triaged items collapse out
(height+opacity, dur-2) and the next item moves up. Pages fade-rise 4px (dur-3). Skeletons, not spinners, for
anything > 300ms. Optimistic UI for done/grade/accept/bin with undo toast instead of confirm dialogs.

Voice: plain, second person, short. Errors say what happened + what to do ("Couldn't load failures — Retry").
No raw HTTP codes, no backend enum values ("youtube_channel" → "YouTube channel"), no "clean." jokes next to
errors. Empty states: one line why + one button.

## 4. What a 10/10 looks like (per skill: hierarchy, states, journey, specificity, mobile, a11y)
- Every screen answers in 3 seconds: where am I, what's the ONE next thing, how far along am I.
- One primary button per view, solid amethyst; everything else secondary/ghost.
- All states designed: loading (skeleton), empty (why + 1 action), error (message + retry), partial, success.
- The app moves the user forward: after finishing a thing, the next thing is already in front of them.
- 44px touch targets, visible focus rings, AA contrast, labelled inputs, aria-live for async status.
- Consistent: same control looks/behaves the same on every page, PWA and extension included.
FLOWS: see §5 (filled from the flow inventory).

## 5. Flow refactor targets (from the flow inventory, 2026-10-11)
Global themes: nothing auto-starts/auto-advances; 3 feedback mechanisms (Toast, feedNotice, inline) and NO
Toast mounted in the PWA; no global capture on the web; one 360ms dead fade between routes; bare "Loading…".

G1 Feedback: ONE mechanism — `Toast` supports `{message, action?:{label,onClick}, tone}`; mounted in App AND
   pwa/MobileLayout; feedNotice migrated onto it. Async status inline uses `aria-live`.
G2 Route transitions: replace AnimatePresence mode="wait" (fade-out THEN fade-in) with an enter-only
   `PageTransition` (fadeRise, dur-3, no exit wait). Same wrapper in MobileApp.
G3 Loading: `Skeleton` everywhere data loads; never show an empty-state message before the first fetch resolves.
G4 Global Quick Capture (web): a "+ Capture" button in NavBar (and `c` shortcut) opening a small popover that
   reuses TrackAddBox's classify/capture logic without a track (URL / file / text→note). Success toast has
   "View in Inbox". Uses existing capture APIs only.

Per flow (today → target):
- Review (~4 clicks/note → 1 key): auto-open first due item on mount; keys 1-4 = Hard/Good/Easy/Very easy, Enter
  = Good; after grading a read-note, advance automatically (show "Graded Good" for ~800ms in the list row);
  flashcard result screen keeps its summary but its primary "Next note" is auto-focused (Enter continues);
  empty queue = EmptyState "All caught up — next due <when>"; list sorted by due date if the data has it (if
  not, leave order and report).
- Learn Feed: Learn opens on Feed when feedPending > 0 else Inbox; card exit animates (fly out in swipe
  direction, next card scales up); accept toast "Sent to Inbox · View"; skeleton card while loading.
- Learn Inbox (1 click/note, keep): after a group is exhausted continue into the next group; Enter = Save & file;
  Discard → optimistic + Undo toast (only if an undo path exists; else keep it and add a confirm-free 5s
  "Discarding…Undo" delay client-side); inbox row titles readable (folder chip shrinks first, title gets min
  width); title not duplicated in preview; Read mode default, RSVP as a secondary toggle.
- Tracks (~6-7 clicks → 3): empty-state CTA opens Manage with NewTrackForm title focused; after create, if no
  deadline, PUT a default schedule of 1 item/day all 7 days (existing schedule API — a default, not a model
  change), then switch to Today where the new items are visible (pending-ingest items shown as "processing"
  rows); Mark done → row collapses out (listItem) + toast "Done"; "add to spaced review" becomes a per-item
  toggle remembered per track.
- Notes: Cmd/Ctrl+S saves; visible save state ("Saved" / "Unsaved changes" / error) in the editor header; Cancel
  with unsaved changes asks inline (not window.confirm); save errors caught and toasted; mobile: Files button is
  a full-width 44px control and the empty note pane shows ONE "Select a note" message.
- Capture results everywhere (PWA Capture, extension, Quick Capture): success shows where it went + link.
- Failures (desktop): add a "Failed captures" section (api/capture.js fetchFailedCaptures/retry/dismiss) with
  Retry + Dismiss per row; "Resolve all older than 7 days" for pipeline failures; errors → ErrorState+Retry;
  never "Nothing open — clean." while an error is shown; a count badge on the Failures nav item.
- Settings: autosave each simple field on change/blur with an inline "Saved ✓" (dur-2 fade); keep explicit
  buttons only for vault path (re-index) and credentials; group into Vault · Learning · Feed · Sync · Advanced;
  long paths/URIs wrap + copy button; host-only items explain themselves in one human sentence.
- Subscribe (web Feed ▸ Sources, PWA): copy the extension's preview → confirm pattern; after confirm switch to
  Swipe; human source-kind labels; delete source gets Undo toast.
- PWA shell: bottom nav = Review · Learn · Capture · Tracks · Notes (route MobileNotesPage + MobileSearchPage);
  "Sync" leaves the tab bar → a sync-status chip in TopBar (offline / syncing / synced 2h ago / N failed) that
  opens the Sync page; Review tab shows due-count badge; persistent "Update ready — Reload" banner instead of a
  toast; TopBar shows the page title (not the brand); 44px everywhere.
- Extension popup: fix `#subscribe-form` overriding `[hidden]`; check sign-in on open → sign-in card if signed
  out; "Capture this page" the single primary; paste/drop under a Disclosure (no URL prefill); Subscribe a quiet
  link row; results: success with "Open Learn", duplicate in ember with link, offline-specific message; settings:
  sign-in first, advanced collapsed, show signed-in user + Sign out; focus-visible, aria-live, aria-labels.

## 6. Milestones
F0 runs alone (everyone depends on it). W1-W7 then run IN PARALLEL (disjoint files). F-final runs last.
Shared files (`store/useStore.js`, `App.jsx`, `NavBar.jsx`) — W-agents may make small, local edits inside their
own store slice only; the orchestrator resolves merge conflicts. Each milestone: tests green via test.sh, its
files removed from the styleGuard allowlist, FLOWS.md for touched folders updated, 1-3 commits.

- **F0 Foundation** — tokens.css (new semantic tokens + legacy aliases), base.css (incl. `color-scheme: dark`,
  form elements, focus-visible, reduced-motion), fonts in index.html (Newsreader, Geist, Geist Mono), all
  primitives in §2, motion presets + PageTransition (G2), Toast upgrade + mount in MobileLayout (G1),
  `/styleguide` route, styleGuard test with an allowlist pre-filled with every current offender, DESIGN.md
  rewritten for "Obsidian Glass" (tokens, primitives, rules, Technology Notes). Also restyle NavBar +
  MobileTabBar (they frame every page). Tests: primitives render/aria tests, styleGuard passes.
- **W1 Review** — ReviewPage, ReviewList, FlashcardSession, ReviewRating, inline note review: §5 Review flow.
- **W2 Learn** — LearnPage, FeedView, FeedSources, InboxReview, InboxPanel, RsvpReader, SourceSplicePanel,
  FolderPicker: §5 Learn + Subscribe (web).
- **W3 Tracks** — TracksPage, TrackAddBox, NewTrackForm: §5 Tracks (fixes the Manage-form regression).
- **W4 Notes + Quick Capture** — MainPage, SplitLayout, NotePanel, NoteEditor, NoteViewer, FolderTree, TabBar,
  LinksPanel, ResourcePanel, MilkdownEditor.module.css (colours/fonts only), NewNoteForm, G4 QuickCapture
  (new molecule + NavBar button).
- **W5 Ops pages** — DashboardPage, PipelineFailuresPage, GetAppPage, SettingsPage, LoginModal, QuitGuard,
  RouteErrorBoundary, EditorErrorBoundary: §5 Failures + Settings.
- **W6 PWA shell** — frontend/src/pwa/* + public/manifest.webmanifest theme colours: §5 PWA shell.
- **W7 Extension** — extension/popup.html/css/js, onboarding.html: §5 Extension; replace the hand-copied
  token block with the new palette + a header comment naming tokens.css as the source of truth.
- **F-final (orchestrator + 1 Sonnet QA agent)** — merge W1-W7, remove legacy token aliases, allowlist must be
  empty, full test.sh frontend + java-unit, live browse pass at 1440 and 400 on every route
  (http://172.18.0.6:8081 — container IP; 127.0.0.1:80 is a DIFFERENT app), re-rate each page 0-10, fix
  anything < 7 in one more round, update memory + root FLOWS index.

## 7. Out of scope (do not do)
Light theme; Tailwind/any new UI dependency; backend schema changes; Feed→Tracks bridge (D); notes autosave
(diff-patch sync makes it risky — Cmd+S only); rewriting Milkdown.
