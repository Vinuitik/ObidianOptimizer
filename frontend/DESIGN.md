# Frontend Design Reference

Files: styles/tokens.css, styles/reset.css, styles/globals.css, atoms/Icon.jsx, atoms/ObsidianMark.jsx, atoms/Chip.jsx, atoms/Ring.jsx, molecules/SearchBar.jsx, store/useStore.js, utils/markdown.js

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

---

## Atoms

`atoms/Icon.jsx` — SVG glyph set via `name` prop. Available: `folder, file, chevron, search, plus, sparkle, clock, flame, dot, check, link, settings`. `paths` lookup at module scope — no per-render alloc. Props: `name`, `size`, `color`, `strokeWidth`

`atoms/ObsidianMark.jsx` — pure-CSS faceted obsidian shard mark. Facet data at module scope. Props: `size` (default 120), `glow` (default true). Use `glow=false` in NavBar (no drop-shadow).

`atoms/Chip.jsx` — inline action chip. Renders as `<span>` (static) or `<button>` (if `onClick` passed). Props: `children`, `onClick`, `className`

`atoms/Ring.jsx` — SVG progress ring. Props: `pct` (0–100), `size` (default 44), `strokeWidth` (default 4)

## Molecules

`molecules/SearchBar.jsx` — controlled search input with search Icon, ⌘K / Ctrl+K focus shortcut (global keydown listener), clear button when query non-empty. Props: `value`, `onChange`

---

## State Shape (Zustand — useStore.js)

| Field | Purpose |
|---|---|
| `tree` | nested `{type, children, fullPath}` — vault file tree |
| `vaultRoot` | absolute path to vault root |
| `reviewNotes` | `[{shortName, fullPath}]` |
| `currentNoteHtml` | rendered HTML of open note (display only) |
| `currentNoteRaw` | raw markdown of open note (source of truth) |
| `currentNotePath` | absolute path of open note |
| `isAuthenticated` | `true` after successful login |
| `showLogin` | controls LoginModal visibility |
| `centerMode` | `'view' \| 'new' \| 'edit'` |
| `newNoteFolder` | folder path for new note creation |
| `leftCollapsed`, `rightCollapsed` | panel collapse state |

State is in-memory only — lost on page refresh. `checkAuth()` re-runs on every load via `MainPage` useEffect.

---

## Markdown Rendering (utils/markdown.js)

`renderMarkdown(content)` pipeline:
1. `parseFrontmatter()` — extracts `---` YAML block → renders as HTML `<table>` (key = `<th scope="row">`, value = `<td>`); prepended before body
2. `![[image.png]]` → `<img src="/api/images/image.png" class="embedded-image">`
3. `[[link|alias]]` or `[[link]]` → `<a class="wiki-link" data-wiki-link="link" href="#">label</a>`
4. `#hashtag` → `<span class="md-tag">#tag</span>` (indigo pill; skips `# Headings`)
5. `markdown-it.render()` with `linkify: true`, `typographer: true`, `breaks: true`, tables enabled

`breaks: true` — single newlines become `<br>` (Obsidian-style soft wrapping).  
`.wiki-link` and `.md-tag` styled in `styles/globals.css`.  
Images served via `/api/images/` — path must match `ImageRepository.imageDir`.

**Residual:** No Obsidian-style embedded note preview (`![[Note]]` transclusion).

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
- **Zustand**: in-memory only. No persistence — state lost on page refresh.
- **Google Fonts**: CDN link in `index.html`. Requires internet at load time; falls back to system sans-serif offline. To bundle: add to `public/fonts/` and use `@font-face` in `tokens.css`.
- **CSS Modules**: class names scoped by Vite. Never use global class names inside `.module.css` files.

---

## Change Index

| Thing to change | Where |
|---|---|
| All design tokens (colors, fonts, spacing) | `styles/tokens.css` |
| Accent color | `styles/tokens.css` — all four `--color-accent*` vars |
| Logo mark size/glow | `atoms/ObsidianMark.jsx` |
| Icon glyphs | `atoms/Icon.jsx` `paths` object |
| Markdown rendering pipeline | `utils/markdown.js` |
| Frontmatter table style | `styles/globals.css` `.markdown-body th/td` |
| Wiki-link / hashtag style | `styles/globals.css` `.wiki-link`, `.md-tag` |
| Google Fonts | `index.html` font link + `tokens.css` `--font-*` vars |
| Zustand store shape | `store/useStore.js` |
| Atom props/behavior | individual file in `atoms/` |
