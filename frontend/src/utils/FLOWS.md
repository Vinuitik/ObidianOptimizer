# Utils Flows

Files: diff.js, frontmatter.js, markdown.js, media.js, wikiResolve.js, useSearch.js, offlineSearch.js

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
Strips the `---…---` block + trailing blank lines. `body` is passed to the editor; `frontmatter` kept separately.

`parseFrontmatterFields(frontmatter)` → `[{ key, value }]`  
Splits each line on `:` for display in `FrontmatterTable`.

`joinFrontmatter(frontmatter, body)` → `string`  
Re-attaches frontmatter + body. Called on every `updatePending` keystroke.

**Frontmatter is never passed into the editor** — CmEditor only receives and serializes the body.

---

## markdown.js — read-only renderer (markdown-it)

`renderMarkdown(content)` → `parseFrontmatter` (key/value table HTML) + `md.render(body)`. Used by
`MarkdownView` (Review, Inbox, SourceSplicePanel, PWA notes). One pipeline, all custom syntax are
markdown-it INLINE/BLOCK rules (never raw-text pre-passes), so they cannot fire inside code spans,
fences or math:

```
block  math_block   ($$..$$ , \[..\])  → KaTeX displayMode (error → .math-error <pre>)
inline math_inline  ($..$)               → KaTeX
inline embed        (![[file]], before 'image') → <img|video|audio|a> by extension; src = media.resolveMediaSrc
inline wiki_link    ([[t]] / [[t|a]], before 'link') → <span class="wiki-link" data-wiki-link="t">alias or BASENAME</span>
inline md_tag       (#tag, pushed last)  → <span class="md-tag">   ([[#heading]] never reaches it: wiki_link consumed it)
highlight           Prism (js ts py java bash json yaml sql); unknown lang → plain <pre><code>
```

Embed `src` is the same `mediaUrl` the offline warm (`noteMedia.js` / `pwa/warmMedia.js`) caches, so
`/vault-media/<subdir>/<name>` images work offline via `public/sw.js` cache-first (`isMedia` by extension).
To change a rule: the `md.inline.ruler.*` / `md.block.ruler.*` block in `markdown.js`. Click handling: `MarkdownView.handleClick`.

## media.js — shared media helpers (no editor/markdown dependency)

`mediaUrl(filename)` (offline cache key — do not hand-build elsewhere), `resolveMediaSrc` (pending blob wins),
pending-blob registry (`setPendingBlobs/addPendingBlob/removePendingBlob/onPendingBlobsChange`), `fileTypeFor`,
`isWhitelisted`, `generateFilename`, `WHITELISTED_EXTS/MIME_TYPES`.
To add accepted paste types: `media.js *_EXTS + WHITELISTED_MIME_TYPES`.

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
the `[[` popup in `editor/wikiLinks.js` (via `searchWithFallback`, same offline fallback) both use
this search path, so the offline fallback below covers search AND note-linking with one change.

To change debounce: `useSearch.js setTimeout(..., 500)`  
To change minimum query length: `minLength` arg (default 2; the `[[` popup uses 1)

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

## Change Index

| Thing to change | Where |
|---|---|
| Diff algorithm | `diff.js lcsBacktrack()` |
| Frontmatter split/join | `frontmatter.js` |
| Wiki-link / embed / tag / math render rules | `markdown.js md.inline.ruler / md.block.ruler` |
| Wiki-link regex (rewrite — backend) | `NoteLinkRepository.rewriteLinks()` |
| Paste accepted types | `media.js *_EXTS + WHITELISTED_MIME_TYPES` |
| Filename collision format | `media.js generateFilename()` |
| Offline search fallback trigger | `useSearch.js isServerUnreachable()` |
| Offline search scoring | `offlineSearch.js offlineKeywordSearch()` |
