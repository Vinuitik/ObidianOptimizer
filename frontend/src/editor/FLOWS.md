# Editor (CodeMirror 6) Flows

Files: CmEditor.jsx, CmEditor.module.css, theme.js, livePreview.js, wikiLinks.js, embeds.js, math.js, tables.js, hashtags.js, codeBlocks.js, inCode.js, ../utils/media.js, ../utils/wikiResolve.js, ../utils/frontmatter.js, ../components/molecules/MarkdownView.jsx (read-only twin), ../utils/markdown.js

The document in the editor IS the raw markdown body (no schema, no serializer round-trip). Frontmatter is split off before the view is built and re-joined by the store on every update.

---

## Load note → editor state

```
store.currentNotePath / editorResetKey change
  → CmEditor (outer): no path → "Select a note to get started"
  → EditorErrorBoundary → CmEditorInner
  → useLayoutEffect: setPendingBlobs(pendingFiles)        — blob registry before any widget exists
  → useEffect([currentNotePath, editorResetKey]):
      body = splitFrontmatter(store.pendingRaw).body
      sep  = detectLineSeparator(body)                    — '\r\n' only if EVERY newline is CRLF
      new EditorView({ doc: body, extensions: [lineSeparator?, baseExtensions(...)] })
  → cleanup: clear timer (DROP, not flush), view.destroy()
isMutable change → readOnlyCompartment.reconfigure(readOnlyExt)   — no remount
```

First load is never dirty: nothing calls `updatePending` until a real `docChanged` transaction.
To change what is rebuilt on reset: `store.editorResetKey` (bumped by `logout`).
To change extension order/list: `CmEditor.featureExtensions()` (one line per feature) / `baseExtensions()`.

## Edit → debounced updatePending → save hunks

```
keystroke → transaction → updateListener (docChanged) → onDocChange()
  → setTimeout(flush, 120ms) (reset on each keystroke)
flush() → store.updatePending(view.state.sliceDoc())
  → joinFrontmatter(pendingFrontmatter, body) → pendingRaw
blur / Mod-s keymap → flush() immediately (Mod-s returns false so SplitLayout's window Ctrl+S handler then saves)
mount → store.setEditorFlush(flush); unmount → setEditorFlush(null)   — store.flushSave() calls it first
updatePending → store.scheduleAutosave() (1500 ms) → flushSave() → store.syncNote() → computeHunks(currentNoteRaw, pendingRaw)   — exact; no cleanup pass
```

Dirty = `selectDirty()` on `pendingRaw` vs `currentNoteRaw` in the store (computed on `updatePending`, never per keystroke in the view).
To change debounce: `CmEditor.jsx DEBOUNCE_MS`. To change undo: `history()` in `baseExtensions`.

## Live preview decoration cycle

```
docChanged | selectionSet | viewportChanged | syntax tree changed
  → livePreview.build(view): for each view.visibleRanges → syntaxTree(state).iterate
      → line decorations (headings h1-h6, quotes, list marks) + mark decorations (strong/em/strike/code/link)
      → Decoration.replace({}) hides markers (#, **, `, ~~, [](), >) on lines NOT touching the selection
  → replaced ranges exposed via EditorView.atomicRanges (cursor never lands inside a hidden marker)
Links: data-href → CmEditor click handler: read-only click / edit Ctrl|Cmd+click → window.open
```

Same cycle shape in `hashtags.js` (`.md-tag`, skips code/URL/links) and `codeBlocks.js` (block line styling, dimmed fences off-cursor, `HighlightStyle` token colours; grammars lazy via `@codemirror/language-data`).
To change which markers hide: `livePreview.js build()`. Look/feel: `theme.js` (tokens only). Code palette: `codeBlocks.js highlightStyle`.

## `[[` suggest → semantic search → insert

```
type "[[ab" → autocompletion({ override: [wikiCompletionSource()] })
  → ctx.matchBefore(/\[\[([^\]|]{0,80})$/) ; query >= 1 char ; 80ms debounce ; AbortController on ctx abort
  → searchWithFallback(q) = searchNotes() (semantic, GET /search)
        server unreachable → offlineKeywordSearch() (filename match from cachedNoteNames)
  → options = basename of notePath ; filter:false (server ranks)
  → ArrowUp/Down/Enter/Esc via CM completionKeymap (Enter accept ignored for first 75ms after open)
  → apply: replace query with `basename]]` (consumes an existing `]]`), cursor after
```

Decorations (`wikiLinks.js`): `[[t]]`, `[[t|a]]` → `.wiki-link[data-wiki-link=t]`; off the active line brackets are hidden and display = alias, else BASENAME only (path prefix hidden); on the active line raw. Unclosed `[[x` and anything in code stay plain.
To change popup look: `theme.js` (`.cm-tooltip*`). Trigger/min length/debounce: `wikiLinks.js TRIGGER / DEBOUNCE_MS / wikiCompletionSource`.

## Click navigation

```
click on [data-wiki-link]
  read-only → open ; editing → only with Ctrl/Cmd
  → openWikiTarget(target): opts.onOpenNote?.(target) or resolveWikiTarget(noteIndex, target) → store.openTab(fullPath)
```
`resolveWikiTarget`: full path first, then basename. MarkdownView uses the same function. To change resolution: `utils/wikiResolve.js`.

## Attachments → pending blobs → upload on save

```
paste files | drop files | 📎 picker (edit mode)  → embeds.insertFiles(view, files)
  → isWhitelisted(file) else showToast("N files skipped")
  → generateFilename → URL.createObjectURL → media.addPendingBlob + store.addPendingFile(filename, file, blobURL)
  → view.dispatch(replaceSelection("![[filename]]"))
embed widget: resolveMediaSrc(filename) = pending blobURL, else mediaUrl() = /vault-media/<subdir>/<name>
registry change (tab switch/paste) → onPendingBlobsChange → refreshEffect → rebuild widgets
Save → store.syncNote uploads pendingFiles, then removes them
HTML paste containing <a> → htmlLinksToMarkdown → `[text](href)`
```
Widgets: img / video / audio (kept alive via `eq()`), pdf & other → link; raw text while the cursor is on the line (edit mode); never rendered inside code (`inCode`). Read-only image click → `ImageLightbox`.
`mediaUrl` MUST stay byte-identical to what `utils/noteMedia.js` warms (offline cache key).
To change accepted types: `utils/media.js WHITELISTED_EXTS / MIME_TYPES`. Embed look: `CmEditor.module.css`, `theme.js`.

## Math / tables widgets

```
math.js: StateField → findMath(state): code ranges (FencedCode/CodeBlock/InlineCode) masked via syntaxTree,
         then BLOCK_RE ($$..$$, \[..\]) and INLINE_RE ($..$) → matches
  → Decoration.replace(MathWidget) unless the selection touches the match (raw source shows)
  → renderMath(src, display): katex.renderToString, cached by (mode+source), 500 entries; error → .cm-math-error + raw
  → fast path: edits that add/remove no `$ \ ` ~ newline, don't touch a match and aren't on a line containing `$`/`\`
    only position-map old matches/decorations (no rescan)
tables.js: syntaxTree Table node (top-level) → TableWidget (parseTable) unless the selection is inside
```
Widgets are block/atomic; click (mousedown) moves the cursor in, revealing raw text.
To change KaTeX options/cache: `math.js renderMath`. Table look: `tables.js` baseTheme.

## CRLF

```
load: detectLineSeparator(body) → '\r\n' | null
'\r\n'  → EditorState.lineSeparator.of('\r\n'): doc.sliceString/sliceDoc and Enter use CRLF → unchanged lines round-trip byte-exact
null    → CM normalises to '\n' on read
```
Frontmatter keeps its own endings (split/join is string-based). To change: `CmEditor.detectLineSeparator`.

---

## Technology Notes

- **Viewport rendering**: CM6 only builds DOM/decorations for `view.visibleRanges`. Block widgets (images, video, math, tables) need an `estimatedHeight` (see `EmbedWidget`, `MathWidget`) or scrollbar/scroll position jumps on long notes. `StateField`-provided block decorations (math, tables) cover the whole doc, ViewPlugin decorations (live preview, tags, embeds, wiki-links) only the viewport.
- **Atomic ranges**: any `Decoration.replace` that hides text must be in `EditorView.atomicRanges`, else the caret can sit inside hidden markers. Backspace over an atomic range deletes the whole range.
- **Lezer incremental parse**: `syntaxTree(state)` can be partial on big docs (parse continues in the background; extensions re-run when the tree identity changes). Tests that need the full tree call `ensureSyntaxTree`. Code detection (`inCode`, math masking) is only as good as the tree parsed so far.
- **KaTeX is synchronous**: cached by source string (500-entry FIFO, per page load, lost on refresh). A note with thousands of distinct formulas pays the render once each. 5k-line note with ~2000 math items: full `findMath` scan ~2-3 ms, only run on math-ish edits; ordinary typing ≈ +1-2 ms (jsdom).
- **Debounce / flush / drop-on-destroy**: `pendingRaw` lags the view by up to 120 ms. Blur and Ctrl+S flush. On unmount the pending timer is DROPPED, but every navigation path (tab/note switch, toggle, title commit, window blur) first calls `store.flushSave()` which pulls the text through `setEditorFlush` (tested: `autosave.test.jsx`). A hard crash inside the 120 ms window loses those keystrokes (store is in-memory anyway).
- **CRLF rule**: CRLF is preserved only when EVERY newline in the body is CRLF. A mixed-ending note is opened as LF-normalised, so the first edit saves with all line endings converted to LF (large hunk, content otherwise identical). Lone `\r` is treated as a line break.
- **`[[` popup source**: must be ONE stable function (`autocompletion({ override: [...] })`). A languageData factory returning a new function per call makes CM drop every result and re-query forever. Only one `autocompletion()` may be configured (it lives in `wikiLinks.js`).
- **Pending blobs**: module-level registry (`utils/media.js`), in-memory, keyed by generated filename; blob URLs are revoked on cancel/logout and die on refresh (unsaved attachments are lost with the unsaved text).
- **Read-only twin**: `MarkdownView` renders the same syntax with markdown-it (`utils/markdown.js`), not CM6; the two parsers can disagree on edge cases (e.g. emphasis rules, `$` pairing) — fix both when changing syntax support.
- **Hardcoded**: `/vault-media/...` URL prefix (nginx), `DEBOUNCE_MS=120`, completion debounce 80 ms, math cache 500.

## Change Index

| Thing to change | Where |
|---|---|
| Extension list / order | `CmEditor.featureExtensions()`, `baseExtensions()` |
| Editor→store debounce | `CmEditor.jsx DEBOUNCE_MS` |
| Autosave delay / flush hook | `store.AUTOSAVE_MS`, `store.setEditorFlush` |
| Load / remount rules | `CmEditor` effect deps; `store.editorResetKey` |
| CRLF detection | `CmEditor.detectLineSeparator()` |
| Read-only vs edit | `CmEditor.readOnlyExt`, `store.isMutable` |
| Editor look (headings, code, popup) | `theme.js` |
| Hidden markers / heading sizes | `livePreview.js build()`, `theme.js .cm-md-*` |
| `[[` popup trigger/search | `wikiLinks.js wikiCompletionSource` |
| Wiki-link display / click | `wikiLinks.js build() / openWikiTarget()`; resolution `utils/wikiResolve.js` |
| Embed widgets / paste / drop | `embeds.js EmbedWidget / insertFiles / embedsExtension` |
| Accepted attachment types | `utils/media.js WHITELISTED_EXTS / WHITELISTED_MIME_TYPES` |
| Embed URL (offline cache key) | `utils/media.js mediaUrl()` |
| Math detection / KaTeX | `math.js findMath / renderMath / needsRescan` |
| Table rendering | `tables.js parseTable / TableWidget` |
| `#tag` rules | `hashtags.js TAG_RE`; read-only: `utils/markdown.js md_tag` |
| Code highlight palette / languages | `codeBlocks.js highlightStyle`; `baseExtensions` `codeLanguages` |
| Skip-in-code check | `inCode.js` |
| Read-only renderer | `utils/markdown.js`, `components/molecules/MarkdownView.jsx` |
