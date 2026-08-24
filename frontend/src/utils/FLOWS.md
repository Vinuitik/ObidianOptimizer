# Utils Flows

Files: diff.js, frontmatter.js, markdownCleanup.js, wikiLinkPlugin.js, obsidianImagePlugin.js, hashtagPlugin.js, livePreviewPlugin.js, mathPlugin.js, markdown.js, useSearch.js, offlineSearch.js

---

## diff.js — Diff & Patch

`computeHunks(original, modified)` → `Hunk[]`  
LCS (O(m×n)) → edit ops → compress into hunks. Both sides CRLF-normalized first.

`applyHunks(base, hunks)` → `string`  
Applies hunks back-to-front (high line index first) so earlier indices stay valid. If stale (file changed externally), falls back to unmodified `base`.

`Hunk`: `{ startLine: number, deleteCount: number, insertLines: string[] }`

To change diff algorithm: `diff.js lcsBacktrack()`  
To revert to full-replace on save: call `updateNote(path, content)` (`PUT /api/notes`) instead of `patchNote` in `syncNote()`

---

## frontmatter.js — Frontmatter Split/Join

`splitFrontmatter(raw)` → `{ frontmatter: string, body: string }`  
Strips the `---…---` block + trailing blank lines. `body` is passed to Milkdown; `frontmatter` kept separately.

`parseFrontmatterFields(frontmatter)` → `[{ key, value }]`  
Splits each line on `:` for display in `FrontmatterTable`.

`joinFrontmatter(frontmatter, body)` → `string`  
Re-attaches frontmatter + body. Called on every `updatePending` keystroke.

**Frontmatter is never passed into Milkdown** — Milkdown only receives and serializes the body.

---

## markdownCleanup.js — Output Post-processing

`cleanMilkdownOutput(md)` → `string`  
Strips lines that are only `<br />` / `<br>` / `<br/>` (Milkdown empty-paragraph markers — Obsidian doesn't need them).

Does not touch: fenced code blocks, inline code, wiki-links, hashtags, math blocks, frontmatter.

---

## wikiLinkPlugin.js — `[[target]]` / `[[target|display]]`

| Part | Role |
|---|---|
| `wikiLinkRemark$` | `$remark` — splits text nodes in mdast, inserts `wikiLink` nodes |
| `wikiLinkNode$` | `$node` — ProseMirror atom; `toDOM` → `<span class="wiki-link" data-wiki-link="...">` |
| `wikiLinkInputRule$` | `$inputRule` — typing `[[...]]` creates the node inline |

`toMarkdown` uses `state.addNode('html', ...)` — emits `[[...]]` verbatim, avoids mdast escaping `[`.

**Plugin order**: before `hashtagPlugin` (so `[[#heading]]` is consumed first)

---

## obsidianImagePlugin.js — `![[filename]]` + paste-upload

| Part | Role |
|---|---|
| `obsidianImageRemark$` | `$remark` — splits text, inserts `obsidianImage` nodes |
| `obsidianImageNode$` | `$node` — `toDOM` branches on `fileTypeFor(filename)`: image→`<img>`, video→`<video>`, audio→`<audio>`, pdf/other→`<a>` |
| `pendingBlobRegistry` | Module-level `Map<filename, blobURL>` — checked before `/api/images/` fallback |
| `setPendingBlobs(map)` | Replaces entire registry (tab switch + `pendingFiles` change) |
| `addPendingBlob(fn, url)` | Adds single entry immediately (paste handler, same tick as ProseMirror transaction) |
| `removePendingBlob(fn)` | After upload |
| `isWhitelisted(file)` | MIME type or extension check |
| `generateFilename(file)` | `stem-{Date.now()}{8randomhex}.ext` — collision-safe |

**Plugin order**: before `wikiLinkPlugin` — `![[]]` must be consumed before `[[` scanner.  
**`parseDOM`**: selector `[data-obsidian-image]` (not `img[...]`) — handles video/audio/a too.

To add accepted paste types: `obsidianImagePlugin.js *_EXTS + WHITELISTED_MIME_TYPES`

---

## hashtagPlugin.js — `#tag`

`$remark` splits text, inserts `hashtag` nodes.  
`$node` → `toDOM` → `<span class="md-tag">#tag</span>`.  
`toMarkdown` emits `#tag` verbatim.

**Plugin order**: after `wikiLinkPlugin` so `[[#heading]]` is already consumed.

---

## livePreviewPlugin.js — Syntax Markers on Focus

ProseMirror plugin via `$prose`. On every selection change:
1. `markExtent(doc, cursorPos, markType)` — finds continuous range of mark around cursor
2. `Decoration.inline(...)` with `class="pm-active-mark pm-${markName}"` and `data-md-open`/`data-md-close`
3. CSS `::before`/`::after` show syntax chars; mark styling suppressed

No DOM widget nodes (avoids the browser issue where non-editable widgets adjacent to cursor swallow space input).

To change which marks get live preview: `livePreviewPlugin.js MARK_SYNTAX`

---

## mathPlugin.js — `$...$`, `$$...$$`, `\[...\]`

| Part | Role |
|---|---|
| `mathRemark$` | `$remark` — inline `$...$`; standalone `$$` / `\[` paragraphs |
| `mathInlineNode$` | `$node` — `toDOM` → KaTeX `displayMode:false` into `<span class="math-inline">` |
| `mathBlockNode$` | `$node` — `toDOM` → KaTeX `displayMode:true` into `<div class="math-block">` |

`toMarkdown` preserves original syntax: `$$\n…\n$$` or `\[\n…\n\]`.  
Math nodes are `contenteditable="false"` — read-only atoms.  
KaTeX CSS loaded globally in `main.jsx`.

**Plugin order**: before `wikiLinkPlugin` — `\[...\]` must be consumed before `[[` scanner.

---

## useSearch.js — Debounced Semantic Search Hook

```
query changes
  → clearTimeout(timerRef) — cancel pending debounce
  → abortRef.current?.abort() — cancel in-flight fetch (throws AbortError, ignored)
  → query < minLength → return [], loading=false immediately
  → setTimeout(500ms)
      → new AbortController → store in abortRef
      → searchNotes(q, { signal }) → setResults(data)
      → AbortError caught silently
      → server-unreachable error (TypeError, or ApiError 5xx/530) → offlineKeywordSearch(q)
      → other errors → setResults([])
```

`loading` stays `true` during the 500ms wait — callers can show a spinner immediately on keystroke.  
Cleanup on unmount: clears timer + aborts any in-flight request.

**Why AbortController vs just ignoring stale results**: without abort, every keystroke launches a request that runs to completion on the server and holds a DB + embedder connection. AbortController tells the browser to close the TCP connection so the server I/O unblocks early.

**This is the ONE chokepoint for both consumers** — `SearchBar.jsx` (main search) and
`WikiLinkSuggest` inside `MilkdownEditor.jsx` (note-linking `[[` autocomplete) both call
this hook, so the offline fallback below covers search AND note-linking with one change.

To change debounce: `useSearch.js setTimeout(..., 500)`  
To change minimum query length: `minLength` arg (default 2; WikiLinkSuggest uses 1)

---

## offlineSearch.js — Offline degraded search (2026-08-24)

`offlineKeywordSearch(query, limit=10)` → `[{notePath, snippet}]` — same shape as the
online `/search` response, so `useSearch.js` can swap it in transparently.

```
q = query.toLowerCase()
getMeta('cachedNoteNames') (db.js meta — the FULL vault path list, piggybacked on sync —
                            see pwa/FLOWS.md "offline name cache")
per path: title = filename (no extension); skip if q not a substring of title
score: prefix match ranks above mid-title match; shorter titles rank above longer ones
sort desc → slice(0, limit); snippet = the note's folder path (no content available offline)
```

**This is a NAME (filename) match, not content or semantic search — deliberate, not a
gap to fill later.** First attempt cached per-note embedding VECTORS for offline
cosine-similarity search and scrapped it: real semantic search needs the typed QUERY
embedded too, and there's no offline path to embed new text (the embedder is server-only —
see `ml/FLOWS.md` Technology Notes). Filename match is simpler, cheap, needs no schema
bump (reuses the existing `meta` store), and actually covers the real use cases here —
search-by-title and wiki-link autocomplete — without pretending to be semantic.

To change scoring: `offlineSearch.js`. To widen scope: it already covers the whole vault
(not just the downloaded review subset) since `cachedNoteNames` comes from `GET /names`,
not from `reviewNotes`.

---

## markdown.js

General-purpose markdown utilities (non-Milkdown). Check file for current exports.

---

## Change Index

| Thing to change | Where |
|---|---|
| Diff algorithm | `diff.js lcsBacktrack()` |
| Milkdown output cleanup | `markdownCleanup.js cleanMilkdownOutput()` |
| Frontmatter split/join | `frontmatter.js` |
| Wiki-link regex (extract) | `wikiLinkPlugin.js wikiLinkRemark$` |
| Wiki-link regex (rewrite — backend) | `NoteLinkRepository.rewriteLinks()` |
| Hashtag regex | `hashtagPlugin.js $remark` |
| Live preview marks | `livePreviewPlugin.js MARK_SYNTAX` |
| Math syntax preservation | `mathPlugin.js toMarkdown` |
| Paste accepted types | `obsidianImagePlugin.js *_EXTS + WHITELISTED_MIME_TYPES` |
| Filename collision format | `obsidianImagePlugin.js generateFilename()` |
| Offline search fallback trigger | `useSearch.js isServerUnreachable()` |
| Offline search scoring | `offlineSearch.js offlineKeywordSearch()` |
