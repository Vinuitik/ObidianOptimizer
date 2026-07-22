# Capture Architecture — resource → proposed notes → review

Files (target): db capture table (`CaptureRepository.java`), `notes/NoteIndexRepository.java`
(link columns), `inbox/InboxController.java` (capture-aware listing), `capture/CaptureController.java`,
`workspace/` (retired), embedder `ingest/synthesize.py` + `ingest/verify.py` (new) + `ingest/split_note.py`,
`extension/popup.js` + `extension/content.js` (new), `frontend/.../organisms` (Learn queue).

## STATUS (audited 2026-07-22) — phases 1–2, 3, 5 SHIPPED (folder tree), 3b/4/6 open

| Phase (§6) | State | Evidence |
|---|---|---|
| 1. DB + linking | ✅ done | `capture` table + lifecycle (`CaptureRepository`), `capture-id`/`capture-seq` frontmatter stamped (`publish.stamp_capture`), mirrored into `notes` columns (`NoteIndexRepository`), `InboxController.file()` flips capture → `filed` when the last child leaves `_inbox` |
| 2. Text synthesis | ✅ done | `POST /api/capture {text}` stores the original under `resources/files/` + routes through ingest (`extract_text.py`) |
| 3. Chapter split + ordering | ✅ done | `capture_seq` source-ordering (unchanged). PDF chapter boundaries are now REAL: `extract_pdf._toc_chapters` reads the PDF's embedded outline (`PyMuPDF doc.get_toc()`, no LLM) → notes bucket by first-page into `_inbox/<captureId>/<chapter>/`, stamped `ingest-chapter`. PDFs with no outline (most arXiv-style papers) fall back to flat — no invented chapters. The hard "a plan can't cross a chapter" constraint at OUTLINE time is still not enforced (chapters remain a prompt hint there); only the FOLDER placement is chapter-boundary-exact. |
| 3b. VERIFY (stage 3, §3) | ✗ open | no `ingest/verify.py` — no slop filter, no faithfulness judge, no `quality: needs-review` |
| 4. Capture button (DOM grab) | ✗ open | extension sends only the URL; no `content.js`, no `extract_dom()` — web capture refetches server-side (`extract_web`), which loses paywalled/rendered content |
| 5. Learn queue UI | ✅ done | `_inbox/<captureId>/[chapter]/` real staging folders (LEARN_FOLDERS_ARCH §1) + `InboxReview.jsx` collapsible source/chapter tree (color band, count, per-group find_home, "file the whole folder" — LEARN_FOLDERS_ARCH §5). `GET /api/capture` list endpoint still doesn't exist; the queue is served by `GET /inbox`, not `CaptureRepository.listAll()` directly. |
| 6. Storage cleanup + trash lifecycle | ✗ open | `WorkspaceController` alive, downloads still land in `_workspace/` (`DOWNLOAD_DIR`), source-trash on `filed` is a marked TODO in `InboxController` |

See `LEARN_FOLDERS_ARCH.md` for the full folder-tree design (staging layout, chapter
detection, group-centroid find_home, tree UI). Remaining build order: 3b VERIFY → 4 → 6.

Superseded the resource half of the old link-sniffer plan (file deleted; network-sniffing
is out — we capture the rendered DOM the user can already see; finding media *on* a page
is now `EXTENSION_MEDIA_CAPTURE_ARCH.md`) and unifies `INGEST_AGENT_ARCH.md`
standalone mode with the Learn UI.

> One sentence: **a Capture is one original resource plus the ordered, editable notes an
> agent proposes from it.** The original is the source of truth you review against; the
> notes are proposals you amend — AI never gets the last word, and never auto-files.

---

## 1. The Capture model

```
Capture ──1:N──▶ proposed notes      (one resource produces many notes; never N:N —
   │                                   each note is assumed fully contained in the
   │                                   resource; cross-resource context via [[links]])
   ├─ source resource   (video.mp4 / doc.pdf / page.html / pasted text)  ← kept, then trashed when done
   └─ derived media      (keyframes, PDF figures)                        ← kept; notes embed these
```

`source_type ∈ {text, web_dom, pdf, md, audio, video}`.

### Lifecycle (state machine)
```
processing ──▶ ready ──▶ (user files note 1..N out of _inbox) ──▶ filed ──▶ source → _trash
   │             │                                                   │
 ingest        all notes staged in _inbox,                    all children filed →
 running       linked by capture_id + capture_seq             soft-move SOURCE to _trash
               (status=ready, surfaced in Learn queue)        (NOT delete — re-download is painful;
                                                               derived media stays — notes embed it)
```
- **Only the source is trashed** on completion. Derived keyframes/figures remain because
  filed notes `![[embed]]` them.
- Trash = soft-move to `_trash/` (reuse the note soft-delete mechanism, extended to files).

---

## 2. Storage — one resource root, relationships in the DB

**Retire `_workspace/`.** It duplicated `resources/`. Single media tree:

```
resources/{images,videos,pdf,audio,files}/   ← ALL media (capture sources + derived), nginx-served as today
_inbox/                                       ← proposed notes (triage queue); excluded from FSRS review
_trash/                                       ← soft-deleted sources + notes
_reports/                                     ← agent audit trail (retrieval-ignored)
DB capture table                              ← which file is a source, which notes it made, order, status
```

A file being a *capture source* is recorded in the **DB**, not by living in a special
folder. This is the maintainable choice: one media tree, served one way; the DB holds
relationships. Killing `_workspace/` also removes the `/workspace/<name>` serving path.

### The link must survive a resync
`NoteIndexRepository.forceResync()` truncates `notes` and rebuilds from disk, so a
DB-only `capture_id` would be lost. Therefore:

- **Frontmatter is the source of truth** for a note's capture link:
  `capture-id: <uuid>` + `capture-seq: <int>` written into each proposed note.
- `FrontmatterParser` reads them; `NoteIndexRepository.upsert()` mirrors them into
  `notes.capture_id` / `notes.capture_seq` columns (a rebuildable index for fast joins).
- The `capture` table itself is durable (not derived from disk); it stores
  `source_path`, `source_type`, `source_ref`, `title`, `status`, `created_at`.

`capture_seq` preserves **source order** (chapter 1 before chapter 2, t=0 before t=600),
so Learn never renders chapters alphabetically.

---

## 3. Ingestion — the anti-slop agent harness

Doctrine: **pipeline-first, agent-last.** The LLM is a constrained transformer between
deterministic stages — it never sees raw bytes, never places media, never decides truth.

```
0 EXTRACT  (zero LLM)  bytes → numbered, located segments + derived media    ← ground truth
1 OUTLINE  (LLM)       segments → note plans {title, segment_ids, tags, hint}
2 WRITE    (LLM)       one plan → one note body, citing only its segments
3 VERIFY   (gate)      deterministic slop filter → faithfulness judge → ≤1 rewrite   ← NEW
4 ASSEMBLE (zero LLM)  frontmatter + media-by-location + source backlink + #review
```

Stages 0/1/2/4 exist (`embedder/ingest/`). Stage 3 (`ingest/verify.py`) is new.

### Why slop can't easily get through
1. **Grounding by segment id.** Segments numbered in source order (`bundle.number_segments`).
   OUTLINE assigns every id exactly once; WRITE sees only its note's segments; invalid ids
   dropped, empty plans rejected. The model can't write what it wasn't given.
2. **Two-pass.** Cheap structure validated before expensive prose.
3. **Schema + retry budget.** OUTLINE is JSON, validated, ≤2 corrective retries.
4. **Deterministic assembly.** Frontmatter, media (placed by timestamp/page), source
   backlink injected by code — never the model.

### Stage 3 — VERIFY (the new layer), cheap-first
Per written note, before publish:
- **Deterministic slop filter (no tokens):** regex for filler ("in conclusion",
  "it's important to note", "delve", "in today's world"), title-restatement intros,
  empty `##` sections, length bounds (too short = no value; too long = should split).
- **Faithfulness judge (LLM, closed output):** body + its source segments →
  `{"unsupported": [...]}` (closed list, never free prose). Non-empty → **one** rewrite
  with those claims flagged. Bounded to one rewrite — predictable cost.
- **Coverage check (deterministic):** did the note use its assigned segments or silently
  drop half?
- **Survivors aren't discarded:** a note that still fails gets `quality: needs-review`
  frontmatter so the Learn queue surfaces it first. AI proposes, human disposes.

### Per-type front-ends (all end as: source kept + N ordered proposed notes)
| Source | Extract (zero LLM) | Notes |
|---|---|---|
| text / selection | — | **new OUTLINE/WRITE prompt pair** for prose-in → concept-notes-out |
| web_dom | parse the **sent** DOM (text + `<img>`), not a refetch (`extract_dom()`) | synthesize; figures via existing CLIP keep-mask |
| pdf / md | PyMuPDF / heading parse + **chapter-boundary map** | **one note per chapter, `seq`-ordered**; OUTLINE constrained so a plan can't cross a chapter |
| audio | whisper | synthesize → `split_note` if oversized |
| video | keep source + whisper + CLIP keyframes | one big note → **always `split_note`** → ordered children; parent becomes a hub |

### Observability + routing
- Every stage writes input/raw-LLM/output to `_reports/` (`agent_reports.py`) — audit
  where a bad note came from without re-running.
- LLM via host-wrapper router (free providers first, claude-cli last). **Pin VERIFY's
  judge to a stronger model** — a weak judge is itself a slop source.
- **The final gate is the human.** Notes land as proposals in `_inbox/`, never auto-filed.

---

## 4. Capture button (extension) — capture what you can see

No network sniffing, no server-side scraping (grey area). The extension grabs the
**rendered DOM the user is already authorized to see**.

```
popup "Capture this page" / context menu
  → content.js injected into the active tab
      Readability(document) → article text
      + <img> visible in the article → src (or canvas→dataURL for same-origin)
      + page {url, title}
  → background.js send to backend  (POST /capture with a DOM payload, not just a url)
  → embedder extract_dom(payload)  → bundle → harness (stages 1-4)
```
- Open PDF / YouTube tab: still keep the **original file** (direct download / yt-dlp) as
  the source, because that's the source of truth; the DOM grab is for article pages.
- Auth/paywall handled implicitly: if you can see it rendered, the content script can read
  it. We never bypass anything the user can't already access.

---

## 5. Learn — the study queue

Merge today's disjoint **Library** (`_workspace/` files) + **Inbox** (`_inbox/` notes)
into one **Capture Queue**:
- List of captures with status badges (`processing / ready / needs-review / filed`).
- Select one → resource viewer (pdf/video/audio/text) beside its **ordered** proposed
  notes (`ORDER BY capture_seq`). Each note editable, fileable (→ FSRS review via the
  existing `POST /inbox/file`), or deletable.
- Filing the last note flips the capture to `filed` → source auto-trashed.

---

## 6. Phasing (independently shippable)
1. **DB + linking** — `capture` table, frontmatter-backed `capture_id`/`capture_seq`,
   capture-aware `/inbox`. Backend-only, invisible, safe.
2. **Text synthesis** — new prompt; route text through ingest instead of `POST /notes`.
3. **Chapter split + ordering** — pdf/md note-per-chapter; `split_note` on long A/V.
4. **Capture button** — content-script DOM grab + `extract_dom()`.
5. **Learn queue UI.**
6. **Storage cleanup + trash lifecycle** — retire `_workspace/`, trash source on completion.

---

## Technology Notes (constraints / failure modes)
- **DB is a rebuildable index over the vault; the `capture` table is NOT.** Note↔capture
  links live in frontmatter (survive `forceResync`); the `capture` table is authored
  state and must be backed up with the DB, not regenerated from disk. If the capture
  table is lost, notes keep their frontmatter link but the queue grouping/status is gone
  until rebuilt from `capture-id` frontmatter scan.
- **Trash, never delete, the source.** yt-dlp re-fetch can fail (tokenized URLs expire,
  videos get pulled). Soft-move to `_trash/`; a janitor can hard-delete `_trash/` on a
  long timer if disk pressure demands — separate decision.
- **Only the source is disposable.** Derived keyframes/figures are embedded by filed
  notes; trashing them breaks `![[embed]]`. The `capture` row separates `source_path`
  from derived media.
- **VERIFY rewrite is bounded to one pass.** Unbounded "fix it" loops are their own slop
  generator and a cost sink. One rewrite, then flag for human.
- **Faithfulness judge output is a closed list, not prose.** A judge that free-writes is
  just more text to trust; the closed `{"unsupported": [...]}` schema keeps it auditable.
- **Capture-the-DOM ≠ scraping.** We only read what the authenticated user's browser has
  already rendered. No credential export, no headless refetch of gated content.
- **In-memory ingest jobs.** Restart loses job *status* (bundles persist). A capture stuck
  in `processing` after a restart needs a re-fire path (re-POST /capture or a sweeper).

## Change Index
| Thing to change | Where |
|---|---|
| Capture states / lifecycle | `CaptureRepository` + `InboxController` (file → status), trash trigger |
| Note↔capture link keys | frontmatter `capture-id`/`capture-seq` + `FrontmatterParser` + `NoteIndexRepository.upsert` |
| Slop filter rules | embedder `ingest/verify.py` (regex set) |
| Faithfulness judge prompt / model | `ingest/verify.py` (closed-list prompt; pin model) |
| Chapter-boundary constraint | `ingest/extract_pdf.py` (chapter map) + `synthesize.outline` window alignment |
| Long-note split threshold | `split_note.SPLIT_MIN_CHARS` |
| Text-synthesis prompt | `synthesize.py` (new prompt pair) |
| DOM capture selectors | `extension/content.js` (DOM_SOURCES) |
| Resource root / serving | `MediaController` (`resources/<subdir>`); `_workspace/` retired |
| Trash mechanism for files | extend note soft-delete to resource files |
| Learn queue view | `frontend/.../organisms` (CaptureQueue) |
