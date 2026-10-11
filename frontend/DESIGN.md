# Frontend Design Reference — Obsidian Glass

Files: styles/tokens.css, styles/base.css, styles/reset.css, styles/globals.css, atoms/*.jsx (Button, IconButton, Input, Textarea, Select, Checkbox, Badge, Skeleton, Spinner, Toast, Icon, Chip, Ring, ObsidianMark), molecules/*.jsx (Field, Card, Disclosure, SegmentedTabs, EmptyState, ErrorState, PageHeader, ListRow, TabBar, SearchBar, PanelHeader, NavItem, ReviewRating), motion/presets.js, motion/PageTransition.jsx, pages/StyleguidePage.jsx, store/useStore.js, __tests__/styleGuard.test.js

Identity: a quiet scholar's study cut from volcanic glass. Near-black with a violet cast, one amethyst glint for "act here", ember for "now / due / streak". Serif for thinking (Newsreader), grotesk for doing (Geist). Plan + rationale: `architecture_plans/UI_REDESIGN_PLAN.md`. Visual test bed: `/styleguide` (logged in, not in nav).

---

## Layering (each layer may only use the ones above)

```
styles/tokens.css   palette + semantic tokens — the ONLY place a hex / rgba / font-family literal lives
styles/base.css     zero-specificity (:where) element defaults: button, input (all types), select, textarea,
                    checkbox, :focus-visible, a, h1-h4, ::selection, scrollbar, reduced-motion
atoms/ molecules/   primitives — the only place control APPEARANCE is defined
pages / organisms   *.module.css = LAYOUT ONLY (grid/flex/gap/size/position); colours via var(--…)
```

**Rule: pages are layout-only.** Do not restyle native elements or re-implement buttons/inputs/cards per page — use the primitives. `__tests__/styleGuard.test.js` fails on any `*.module.css` outside `styles/` with a hex/rgb(a)/hsl(a) literal or a literal `font-family` (`font-family: var(--font-…)` is allowed), except files in `styleGuard.allowlist.txt` (legacy; shrinks to empty by F-final).

## Tokens (`styles/tokens.css`)

| Group | Tokens |
|---|---|
| Ground | `--bg #0A0A0F` `--surface #111118` `--raised #181822` `--overlay #20202C` `--scrim` |
| Text | `--text` `--text-2` `--text-3` (muted, ≥4.5:1 on bg) `--on-accent` |
| Lines | `--line` `--line-strong` `--sheen` (1px top highlight) |
| Amethyst | `--accent` `--accent-hover` `--accent-text` `--accent-tint` `--accent-line` |
| Ember | `--ember` `--ember-tint` (now / due / streak / warning) |
| Status | `--success` `--danger` (+ `-tint`, `--danger-line`) |
| Focus | `--focus-ring` (2px bg gap + 2px accent-text) |
| Type | `--font-display`/`--font-reading` Newsreader · `--font-ui` Geist · `--font-mono` Geist Mono; `--fs-12/13/14/16/18/22/28/36`; `--lh-tight/ui/reading`; `--reading-width 68ch`; `--content-max 1200px` |
| Space | `--sp-1..10` (4px scale) |
| Shape | `--r-sm 6` `--r-md 10` `--r-lg 14` `--r-pill`; `--control-h` 36px (44px ≤768px, set in base.css) |
| Motion | `--dur-1 120ms` `--dur-2 200ms` `--dur-3 320ms` `--ease-out` `--ease-in-out` |
| Elevation | `--shadow-1` `--shadow-2` `--glass` (= inset sheen + shadow-1) |

Legacy names (`--color-*`, `--font-body/heading`, `--text-xs…`, `--radius-*`, `--ease-fast…`) are aliases at the bottom of tokens.css (`LEGACY ALIASES — removed in F-final`). Don't use them in new code.

## Primitives (props)

Atoms
- `Button` — `variant` primary|secondary|ghost|danger (`'default'` = secondary, legacy), `size` sm|md, `loading`, `icon` (Icon name), plus any native button prop. Primary = solid accent, white text; one per view.
- `IconButton` — `icon`, **`aria-label` required** (throws), `variant` ghost|secondary|danger, `size`.
- `Input` / `Textarea` / `Select` (`options=[{value,label}]` or children) — `invalid`; forwardRef. Looks come from base.css.
- `Checkbox` — `label`, native props. `Badge` — `tone` neutral|accent|ember|success|danger. `Skeleton` — `lines` or `width`/`height`. `Spinner` — `size`, `label`.
- `Toast` — mounted in App AND pwa/MobileLayout. Store: `showToast('text')` or `showToast({message, action:{label,onClick}, tone:'neutral'|'success'|'error', duration})`.

Molecules
- `Field` — `label`, `hint`, `error`, one child control; wires `id`, `aria-describedby`, `aria-invalid`.
- `Card` (+ `Card.Header title subtitle actions`), `interactive`. `PageHeader` — `title subtitle actions`. `ListRow` — `leading title meta trailing onClick active`.
- `Disclosure` — `title`, `storageKey` (remembers open state in localStorage), `defaultOpen`.
- `SegmentedTabs` — `tabs=[{id,label,badge}]`, `value`, `onChange`; arrow/Home/End keys.
- `EmptyState` — `icon title description action={{label,onClick}}` (ONE action). `ErrorState` — `message detail onRetry`.

Motion (`components/motion`)
- `presets.js`: `fadeRise`, `listItem` (exit collapses height+opacity), `collapse`, `swipe` (+ `…Reduced` opacity-only).
- `PageTransition` — enter-only wrapper, used by App's `AnimatedRoutes` (keyed by pathname) and pwa/MobileApp. No `mode="wait"`.

## Rules

- Hierarchy: one solid-accent primary per view; everything else secondary/ghost.
- States: design loading (Skeleton, not "Loading…"), empty (why + 1 action), error (message + Retry), success for every data view. Never show an empty message before the first fetch resolves.
- Motion: every state change is animated or instant — never a blank flash. Pages fade-rise 4px (dur-3); completed items collapse out (dur-2) and the next moves up; skeletons not spinners for >300ms. Reduced-motion: opacity only (base.css kills CSS durations; framer variants use `…Reduced` / `useReducedMotion`). Optimistic UI + Undo toast instead of confirm dialogs.
- Touch: controls 36px desktop, 44px at ≤768px (`--control-h`). Visible focus ring everywhere. Labelled inputs; `aria-live` for async status.
- Surfaces: cards use `--glass` (1px top sheen). No gradients behind text; no glow except focus.
- Breakpoint: `@media (max-width: 768px)`.
- Voice: plain, second person, short. Errors say what happened + what to do ("Couldn't load failures — Retry"). No raw HTTP codes or backend enums ("youtube_channel" → "YouTube channel"). Empty states: one line why + one button.

---

## State Shape (Zustand — useStore.js)

| Field | Purpose |
|---|---|
| `tree` | nested `{type, children, fullPath, loaded}` — vault file tree (lazily populated) |
| `vaultRoot` | absolute path to vault root |
| `noteIndex` | `Map<string, string>` — basename lowercased → full path (for wiki-link resolution) |
| `currentNoteRaw` | raw markdown of open note as loaded from disk (source of truth for diffing) |
| `currentNotePath` | absolute path of open note |
| `pendingRaw` | working copy of raw markdown — updated on every Milkdown keystroke |
| `pendingFrontmatter` | frontmatter string extracted from `currentNoteRaw` (read-only; not passed to Milkdown) |
| `pendingTitle` | note filename without `.md` — editable in header when `isMutable` |
| `isMutable` | `true` when editor is in edit mode — controls Milkdown `editable` prop |
| `editorResetKey` | integer, incremented by `cancelEdit()` — forces Milkdown remount without disk fetch |
| `tabs` | `[{path, pendingTitle, isMutable, hunks}]` — open tab list; `hunks` stores diff from `currentNoteRaw` → `pendingRaw` for inactive tabs |
| `activeTabIndex` | index into `tabs[]`, `-1` when no note open |
| `reviewNotes` | `[{shortName, fullPath}]` — current review queue page |
| `reviewOffset` | pagination offset for review queue |
| `reviewHasMore` | whether more review notes exist past current page |
| `isAuthenticated` | `true` after successful login |
| `showLogin` | controls LoginModal visibility |
| `centerMode` | `'view' \| 'new'` — `'new'` shows NewNoteForm; edit mode is `isMutable` not a centerMode |
| `newNoteFolder` | folder path for new note creation |
| `newNoteName` | controlled input value for new note name |
| `leftCollapsed`, `rightCollapsed` | panel collapse state |

State is in-memory only — lost on page refresh. `checkAuth()` re-runs on every load via `MainPage` useEffect.

---

## Markdown Rendering

The app uses **Milkdown** (ProseMirror-based WYSIWYG) with **both** `@milkdown/preset-commonmark` (125 plugins — provides all base nodes including the required `doc` root) and `@milkdown/preset-gfm` (48 plugins — adds tables, task lists, strikethrough on top). Both must be loaded; `gfm` alone omits `doc` and causes a ProseMirror schema crash. `utils/markdown.js` is a legacy file and is no longer used in the main editor flow.

### Milkdown rendering pipeline

Milkdown receives the note body (frontmatter stripped). Obsidian-specific and extended syntax is handled by custom plugins:

| Syntax | Plugin | Output |
|---|---|---|
| `![[image.png]]` | `obsidianImagePlugin` | `<img class="embedded-image" src="/api/images/image.png">` |
| `[[link]]` / `[[link\|alias]]` | `wikiLinkPlugin` | `<span class="wiki-link" data-wiki-link="link">` |
| `#tag` | `hashtagPlugin` | `<span class="md-tag">#tag</span>` |
| `$...$` | `mathPlugin` | KaTeX inline render (`<span class="math-inline">`) |
| `$$...$$` | `mathPlugin` | KaTeX block render (`<div class="math-block">`) |
| `\[...\]` | `mathPlugin` | KaTeX block render, serializes back as `\[` |
| GFM tables | `gfm` preset | `<table>` with styled `<th>`/`<td>` |
| Standard markdown | `gfm` preset | standard ProseMirror nodes |

Frontmatter is extracted via `splitFrontmatter()` and rendered as a read-only `<table>` by `FrontmatterTable` above the editor surface.  
Images served via `/api/images/` — path must match `ImageRepository.imageDir`.

`.wiki-link`, `.md-tag`, `.math-inline`, `.math-block` styled in `MilkdownEditor.module.css` (scoped to `.ProseMirror`).

### Live preview

`livePreviewPlugin` (`utils/livePreviewPlugin.js`) shows raw syntax markers when the cursor is inside a formatted mark:

| Mark | Shown when cursor inside |
|---|---|
| `em` (`*`) | `*text*` |
| `strong` (`**`) | `**text**` |
| `code` (`` ` ``) | `` `code` `` |

Syntax characters are CSS `::before`/`::after` pseudo-elements on the inline decoration span — no DOM nodes inserted into the editable flow, so space and other input works normally at mark boundaries.  
To change syntax chars: `utils/livePreviewPlugin.js` `MARK_SYNTAX`.  
To change visual style: `.pm-active-mark::before/::after` in `MilkdownEditor.module.css`.

---

## Responsive Breakpoints

| Breakpoint | Behavior |
|---|---|
| ≤600px (mobile) | NavBar links shrink, padding reduced |
| ≤768px (tablet) | Left + right sidebars hidden (`display:none` in `SplitLayout.module.css`) |
| All sizes | Center panel always visible — full width on mobile |

---

## Technology Notes

- **Google Fonts (CDN)**: Newsreader/Geist/Geist Mono load from `fonts.googleapis.com` in `index.html` (`display=swap`). Offline or blocked → the PWA silently falls back to the system stacks in `--font-*` (Georgia/system-ui/ui-monospace): layout holds, identity softens. The service worker does not precache the font files. To self-host: put woff2 in `public/fonts/` + `@font-face` in `tokens.css`.
- **CSS variables can't be used in `@media`**: breakpoints (`768px`) are hard-coded literals; `--control-h` switches via a media query in `base.css`. Changing the breakpoint means editing every module that has one.
- **base.css uses `:where()`** so it has zero specificity and any component class wins. Side effect: a raw element is styled only if nothing more specific (including old per-page CSS) overrides it — legacy page CSS still wins until migrated.
- **Framer Motion** (`framer-motion`): ~tens of KB gzipped in the main bundle; used by PageTransition and Disclosure. Purely visual; honour `useReducedMotion`. Route transition is enter-only, so the outgoing page unmounts instantly (no exit animation).
- **styleGuard is a regex, not a CSS parser**: it misses colour names (`red`, `white`), `color-mix()`, colours in `@import`ed/global CSS, inline `style={{…}}` in JSX, and literals inside `url()` (stripped on purpose). It does not check `styles/` (the allowed home). `font-family: var(--…)` passes. Stale allowlist entries fail the test.
- **Disclosure state** is `localStorage` (per device, wrapped in try/catch; private mode → default open state each load).
- **Toast** is a single global slot in Zustand (in-memory): a new toast replaces the current one; lost on reload; auto-dismiss 4s (7s with an action). Z-index 9999.
- **Zustand**: in-memory only. State lost on page refresh. `editorResetKey` also resets to 0.
- **CSS Modules**: class names scoped by Vite. Never use global class names inside `.module.css` files.
- **KaTeX / Milkdown**: unchanged — see Markdown Rendering above. `@milkdown/preset-commonmark` + `preset-gfm` must be used together; `MilkdownProvider` takes the `key`.

---

## Change Index

| Thing to change | Where |
|---|---|
| Any colour, font, radius, spacing, motion value | `styles/tokens.css` |
| Brand accent | `styles/tokens.css` — all five `--accent*` vars together |
| Element defaults (inputs, focus ring, selects, a, headings) | `styles/base.css` |
| Fonts | `index.html` font link + `tokens.css` `--font-*` |
| Control height (36/44px) | `tokens.css` `--control-h` + `base.css` mobile override |
| Button look / variants | `atoms/Button.module.css` |
| Toast API / look | `atoms/Toast.jsx`, `store/useStore.js` `showToast` |
| Page transition | `motion/PageTransition.jsx`, `motion/presets.js` `fadeRise` |
| Add a primitive | `atoms/` or `molecules/` + a section in `pages/StyleguidePage.jsx` + a test in `__tests__/primitives.test.jsx` |
| Colour-literal rule / exceptions | `__tests__/styleGuard.lib.js`, `styleGuard.allowlist.txt` |
| Nav items | `organisms/useNavItems.js` |
| NavBar / phone tab bar look | `organisms/NavBar.module.css`, `MobileTabBar.module.css` |
| Logo mark | `atoms/ObsidianMark.jsx` |
| Icon glyphs | `atoms/Icon.jsx` `paths` |
| Markdown pipeline | `utils/wikiLinkPlugin.js`, `utils/obsidianImagePlugin.js`, `utils/markdownCleanup.js`, `utils/mathPlugin.js` |
| Editor CSS (wiki-link, math, live preview, height chain) | `organisms/MilkdownEditor.module.css` |
| Live preview syntax chars | `utils/livePreviewPlugin.js` `MARK_SYNTAX` |
| Frontmatter split/join | `utils/frontmatter.js` |
| Zustand store shape | `store/useStore.js` |
| Tab bar UI | `molecules/TabBar.jsx` + `TabBar.module.css` |
