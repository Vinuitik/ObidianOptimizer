# CM6 Editor Plan — replace Milkdown with CodeMirror 6 Live Preview

Branch: `cm6-editor`. Goal: Obsidian-like editing feel (raw markdown is the document, syntax
hidden off the cursor line, viewport-only rendering), lower latency, no freezes, zero feature
loss. User compares feel on the branch dev server vs live (:8081), then decides to merge.

## Architecture

- `frontend/src/editor/` — new module, one CM6 extension per file, each exporting `xxxExtension(opts) → Extension[]`:
  - `CmEditor.jsx` — React mount (EditorView in a ref; no remount per keystroke), store wiring, read-only via `EditorState.readOnly` + `EditorView.editable` Compartment.
  - `livePreview.js` — syntax-tree (`@lezer/markdown` GFM) decorations: hide `#`, `*`, `**`, `` ` ``, `~~`, `[]()` markers, list/quote markers styled, everywhere EXCEPT lines touching the selection. Viewport-only (`view.visibleRanges`).
  - `wikiLinks.js` — `[[target]]` / `[[target|alias]]` mark decoration (`.wiki-link[data-wiki-link]`), Ctrl/Cmd+click (edit) or click (read-only) → open note; `[[` popup = `@codemirror/autocomplete` source backed by EXISTING `useSearch`/`searchNotes` (semantic) + offline fallback. Link may stay open/unclosed. Keyboard nav (↑/↓/Enter/Esc).
  - `embeds.js` — `![[file]]` block/inline widgets: img / video / audio / pdf+file link via `obsidianImagePlugin.js` helpers (`mediaUrl` MUST stay byte-identical — offline cache key), pending-blob registry lookup, widget `eq()` so playing media isn't recreated; image click → ImageLightbox. Paste / drop / 📎 picker via `domEventHandlers` reusing `insertFiles` logic (whitelist, `generateFilename`, `addPendingBlob`, `onFilePaste`, skipped-file toast). HTML paste with `<a>` → markdown links (`htmlLinksToMarkdown`).
  - `math.js` — `$..$`, `$$..$$`, `\[..\]` → KaTeX widgets (cached render by source string) when cursor not inside; raw source when inside.
  - `tables.js` — GFM table → rendered `<table>` widget when cursor outside; raw text when inside.
  - `hashtags.js` — `#tag` mark decoration `.md-tag`.
  - `codeBlocks.js` — fenced code highlighting via `@codemirror/language-data` (lazy languages).
  - `theme.js` — CM theme mapping to existing tokens (`styles/tokens.css`), headings h1–h4 sizes, read-only look.
- `frontend/src/components/molecules/MarkdownView.jsx` — read-only renderer on `markdown-it` (extend `utils/markdown.js` with wikilink / embed / math(KaTeX) / hashtag / prism rules). Replaces `NoteRenderer` in ReviewPage, InboxReview, SourceSplicePanel. Must NOT be contentEditable (Review 1–4/Enter + Inbox Enter shortcuts rely on it). Same click behaviours (wikilink → openTab, external → window.open, image → lightbox).
- Store contract unchanged: editor writes body via `updatePending(body)`; frontmatter split/join stays in `utils/frontmatter.js`; save = `computeHunks` (now exact — no `cleanMilkdownOutput`). Dirty check must not do full-string compares per keystroke (use a doc-changed flag / version counter).
- Removal (final milestone): Milkdown deps, `utils/*Plugin.js` (Milkdown-specific parts), `markdownCleanup.js`, `NoteRenderer.jsx`, `MilkdownEditor.*`. Keep shared helpers (move to `editor/` or `utils/media.js`).

## Milestones (Sonnet agents, worktrees off `cm6-editor`)

- **M0 foundation (serial)** — deps (`@codemirror/state view commands language autocomplete lang-markdown language-data`, `@lezer/markdown`), `CmEditor.jsx` + `theme.js` + `livePreview.js` (headings/emphasis/code/strike/links/lists/quotes), wired into `SplitLayout` replacing `MilkdownEditor`; save/cancel/Ctrl+S/dirty/tab-switch/resetKey/undo work; `MarkdownView.jsx` read-only renderer (plain markdown first) swapped into Review/Inbox/SourceSplice. Gate: `./linux_scripts/test.sh frontend` green, `vite build` ok.
- **W1–W4 (parallel, each touches only its own `editor/*.js` + one line in CmEditor's extension list + its MarkdownView rule)**:
  - W1 wikiLinks (+ suggest popup, click nav, MarkdownView wikilink rule)
  - W2 embeds + attachments (+ paste/drop/picker, lightbox, MarkdownView embed rule)
  - W3 math + tables (+ MarkdownView KaTeX rule)
  - W4 hashtags + codeBlocks + perf pass (dirty-check, no global listeners, large-note test 5k lines)
- **M-final (serial)** — merge W1–W4, port/replace Milkdown tests (paste, codeblock), remove Milkdown, update `components/FLOWS.md` + new `editor/FLOWS.md`, full frontend suite green.

Each agent: `git merge cm6-editor` first; copy `frontend/node_modules` if missing; run `./linux_scripts/test.sh frontend`; commit (no push, no --no-verify); report files changed + gaps in ≤15 lines.

## Evaluation

Branch dev server: `vite --host` on :5173 with `/api` and `/vault-media` proxied to the live frontend (`http://172.18.0.6:8081`, path kept) → user compares against live :8081 (Milkdown). No feature flag; merge decision after evaluation.

## Feature parity checklist (from code inventory 2026-10-19)

CommonMark+GFM (tables, tasks, strike, autolinks) · prism code · math inline/block/`\[..\]` KaTeX, source preserved · `#tag` · frontmatter hidden + preserved · `[[t]]`/`[[t|a]]` + semantic suggest + offline fallback · read-mode link click → openTab (noteIndex full path then basename), external → new tab · `![[f]]` img/video/audio/pdf/file, `/vault-media/...` URLs unchanged · paste/drop/📎 attach w/ whitelist, rename, blob preview, deferred upload, per-tab pending files, cancel revokes · image lightbox · HTML-link paste · undo/redo · Ctrl+S · cancel w/ discard confirm · hunk save · first-load not dirty · editor error boundary · empty state "Select a note to get started" · read-only views non-contentEditable.

## Technology Notes

- CM6 renders only the viewport; block widgets need `estimatedHeight` or scroll jumps on long notes.
- Decorations that replace text must be atomic (`EditorView.atomicRanges`) or the cursor lands inside hidden markers.
- Lezer markdown parses incrementally and may lag on huge docs (`syntaxTree` can be partial; use `ensureSyntaxTree` with a time budget only where needed).
- KaTeX is synchronous; cache by source string or long math-heavy notes stutter on scroll.
- Milkdown and CM6 bundles must not both ship after M-final (bundle size).
