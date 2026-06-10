# Frontend Design Reference

Files: styles/tokens.css, styles/reset.css, styles/globals.css, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, atoms/Button.jsx, molecules/SearchBar.jsx, molecules/FrontmatterTable.jsx, molecules/PanelHeader.jsx, molecules/NavItem.jsx, molecules/ReviewRating.jsx, molecules/TabBar.jsx, store/useStore.js, utils/frontmatter.js, utils/markdownCleanup.js

---

## Design Tokens

`styles/tokens.css` — all CSS custom properties (colors, spacing, radii, fonts, transitions)  
`styles/reset.css` — box-sizing, base font, scrollbar  
`styles/globals.css` — imports tokens + reset, stagger-in keyframe, markdown body styles  
Fonts (Google Fonts in `index.html`): Space Grotesk (headings) / DM Sans (body) / DM Mono (code/labels)

### Colors

| Token | Value | Use |
|---|---|---|
| `--color-accent` | `#7c5cff` | Primary amethyst |
| `--color-accent-soft` | `#a68dff` | Text on dark bg |
| `--color-accent-dim` | `rgba(124,92,255,0.14)` | Tinted backgrounds |
| `--color-accent-border` | `rgba(124,92,255,0.34)` | Bordered highlights |
| `--color-amber` | `#e0a458` | Streak display |
| `--color-success` | `#4cc38a` | Positive states |

To change accent: update all four `--color-accent*` vars in `tokens.css` together.  
To change any color/spacing/font: `tokens.css` only — never edit component CSS directly for tokens.

### Radii

`--radius-xs: 8px` / `--radius-sm2: 9px` / `--radius-md2: 11px` / `--radius-lg2: 12px`

### Spacing

`--sp-1: 4px` through `--sp-10: 96px` (4 → 8 → 12 → 16 → 24 → 32 → 48 → 64 → 80 → 96)

---

## Atoms

`atoms/Icon.jsx` — SVG glyph set via `name` prop. Available: `folder, file, chevron, search, plus, sparkle, clock, flame, dot, check, link, settings`. `paths` lookup at module scope — no per-render alloc. Props: `name`, `size`, `color`, `strokeWidth`

`atoms/ObsidianMark.jsx` — pure-CSS faceted obsidian shard mark. Facet data at module scope. Props: `size` (default 120), `glow` (default true). Use `glow=false` in NavBar (no drop-shadow).

`atoms/Chip.jsx` — inline action chip. Renders as `<span>` (static) or `<button>` (if `onClick` passed). Props: `children`, `onClick`, `className`

`atoms/Ring.jsx` — SVG progress ring. Props: `pct` (0–100), `size` (default 44), `strokeWidth` (default 4)

## Molecules

`molecules/SearchBar.jsx` — controlled search input with search Icon, ⌘K / Ctrl+K focus shortcut (global keydown listener), clear button when query non-empty. Props: `value`, `onChange`

`molecules/TabBar.jsx` — browser-style tab strip. Each tab shows note title, dirty indicator (•), and close button. Reads `tabs`, `activeTabIndex` from store. Calls `switchTab(i)` / `closeTab(i)`.

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

- **Framer Motion**: page-level route transitions (`AnimatePresence` in `App.jsx`). Purely visual — no state tied to animation.
- **Zustand**: in-memory only. No persistence — state lost on page refresh. `editorResetKey` is also in-memory (resets to 0 on reload).
- **Google Fonts**: CDN link in `index.html`. Requires internet at load time; falls back to system sans-serif offline. To bundle: add to `public/fonts/` and use `@font-face` in `tokens.css`.
- **CSS Modules**: class names scoped by Vite. Never use global class names inside `.module.css` files.
- **KaTeX**: bundled client-side via `katex` npm package. Math is rendered synchronously in `toDOM`. KaTeX CSS loaded in `main.jsx`. Math nodes are `contenteditable="false"` atoms — no inline editing. To change math rendering: `utils/mathPlugin.js`.
- **@milkdown/preset-commonmark + @milkdown/preset-gfm**: must be used together. `gfm` is NOT a self-contained superset — it only ships 48 GFM-specific plugins (tables, strikethrough, task lists) and depends on `commonmark`'s 125 base plugins for the ProseMirror schema root (`doc`) and all standard nodes. Using `.use(gfm)` alone throws `Schema is missing its top node type ('doc')` at schema compile time.
- **Custom `$node` plugins** (`mathPlugin`, `wikiLinkPlugin`, etc.): `$remark()` returns `MilkdownPlugin[]` and must be spread into the plugin array (`[...hashtagRemark$, hashtagNode$]`). `$node()` returns a single plugin — no spreading. `parseMarkdown.runner` receives `(state, node, type)` — use `type` directly, not `state.type(name)`.
- **MilkdownProvider key**: the `key` prop must be on `MilkdownProvider` (not on the inner component). Keying the inner component only lets the provider's schema context persist across remounts, which causes schema corruption on the second editor init.

---

## Change Index

| Thing to change | Where |
|---|---|
| All design tokens (colors, fonts, spacing) | `styles/tokens.css` |
| Accent color | `styles/tokens.css` — all four `--color-accent*` vars |
| Logo mark size/glow | `atoms/ObsidianMark.jsx` |
| Icon glyphs | `atoms/Icon.jsx` `paths` object |
| Markdown rendering pipeline | `utils/wikiLinkPlugin.js`, `utils/obsidianImagePlugin.js`, `utils/markdownCleanup.js` |
| Math rendering | `utils/mathPlugin.js` |
| Math block/inline styles | `organisms/MilkdownEditor.module.css` `.math-block` / `.math-inline` |
| Frontmatter split/join | `utils/frontmatter.js` |
| Frontmatter table style | `molecules/FrontmatterTable.module.css` |
| Wiki-link / image style | `organisms/MilkdownEditor.module.css` (scoped to `.ProseMirror`) |
| Milkdown height chain / editor CSS | `organisms/MilkdownEditor.module.css` |
| Live preview syntax chars | `utils/livePreviewPlugin.js` `MARK_SYNTAX` |
| Live preview CSS style | `organisms/MilkdownEditor.module.css` `.pm-active-mark::before/::after` |
| Google Fonts | `index.html` font link + `tokens.css` `--font-*` vars |
| Zustand store shape | `store/useStore.js` |
| Atom props/behavior | individual file in `atoms/` |
| Tab bar UI | `molecules/TabBar.jsx` + `TabBar.module.css` |
