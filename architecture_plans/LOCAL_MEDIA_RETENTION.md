# Local Media + Retention — everything in ONE UI

Goal (user): when we capture a video/audio/PDF, **pull the real bytes in, ingest one→many,
and play/render the file inside the review UI** — never an external viewer link. Keep the
file only if the user keeps a fragment from it; otherwise trash it. Track its path so the
trash step is O(1), not a vault scan.

## Why (the two bugs that started this)
- **PDF → 1 note:** the "Introduction to Agents.pdf" capture was the Google Drive *viewer
  page* scraped as text (bundle `da8c4b6f4b68`: type=text, 1 segment). The PDF bytes were
  never fetched, so `extract_pdf` (150 pages → many notes) never ran.
- **Video → external link:** yt-dlp downloaded `_workspace/Lesson 4….mp4` (329 MB), but the
  39 notes carry `source: youtube.com/…`, so `SourceSplicePanel` embeds YouTube. The note
  and the downloaded file were never linked.

Root cause is one thing: **the note stores a pointer to an external viewer; the UI never
learns the real file is on disk.**

## Decision (locked)
Download to **`resources/`** (nginx already serves it at `/vault-media/`), **track the path**
in a durable record, ingest one→many, and let **retention** trash the file when no fragment
of it is kept. Mirrors the existing `_workspace` model (`workspace_items` is DB-backed, not a
scan) and reuses the already-built `ingest/retention.py` planner.

## Seams (verified)
- nginx: `/vault-media/ → resources/`, `/workspace/ → _workspace/` (both do range requests).
- `ingest/retention.py` — `compute_retention(ir, units, kept_fragments, source_blob)` →
  `RetentionPlan{keep_paths, drop_paths, keep_transcript, referenced_pages}`. **Planner built +
  tested; the FS-sweep executor is NOT wired** (this doc wires it).
- `download/downloader.py` — yt-dlp fetch (currently → `_workspace/`).
- Java `WorkspaceController` + `workspace_items` table — precedent for path tracking.
- Note source footer: `synthesize._source_section()` writes `## Source`. We add a `local:` line.
- Frontend: `inboxParse.parseSourceRegion` now reads `local:`; `SourceSplicePanel` prefers it
  (STAGE 1, done) — plays local video/pdf via `mediaUrl()` → nginx alias, external as fallback.

## Path tracking — where the pointer lives
`local: resources/<kind>/<file>` in each note's `## Source` footer (all siblings of one
capture point at the same file) **and** the canonical `(capture_id → local_path)` on the
**capture row** (Java) so retention deletes in O(1) without scanning notes. Capture row is the
owner; note footers are for the UI.

## Stages
1. **Frontend prefers local** ✅ DONE (this commit). `parseSourceRegion.local` +
   `SourceSplicePanel` local `<video>`/`<embed>` + `mediaUrl()` nginx routing. No-op until a
   `local:` field exists, so safe to ship first.
2. **Ingest owns the download → resources + stamp `local:`.** [NOT IMPLEMENTED]
   Video/audio/PDF capture: download bytes to `resources/<kind>/` (reuse `downloader`/save),
   transcribe/extract from the **local file**, write `local:` into every note's `## Source`,
   and return the path. Removes the extension's separate `/download` call for these types
   (kills the "fetched twice" + the note/file disconnect). *Big change to the live pipeline —
   needs Docker smoke-test; can't verify in sandbox.*
3. **Capture row stores `local_path`.** [NOT IMPLEMENTED] Java: add `local_path` to the
   capture record; `/capture` (or the internal capture create) persists it.
4. **Retention sweep executor.** [NOT IMPLEMENTED] On capture **acknowledge/file** → if any
   resulting note (or a user `KeptFragment`) references the file, keep it; on **discard / no
   keep** → move `resources/<file>` to `_trash` via the tracked `local_path`. Consumes
   `retention.RetentionPlan`. O(1) delete off `capture.local_path`.
5. **PDF acquisition for viewer-wrapped sources (Drive etc.).** [NOT IMPLEMENTED — hard]
   Direct `.pdf` URLs + dropped files: tractable (fetch bytes → resources). Google Drive
   hides bytes behind a canvas viewer → needs the Drive download URL (`uc?export=download&
   id=…`) with the user's session; may fail on private files. Separate sub-task; may just tell
   the user "open the raw PDF / download and drop it" when bytes aren't reachable.

## Blocker carried over
The extension's **PDF button + file-drop currently make ZERO backend calls** (client-side
failure in Firefox — console error still needed). Stage 5 depends on that path working at all.

## Change Index
| Thing | Where |
|---|---|
| Local file pointer in note | `synthesize._source_section()` adds `local:` [stage 2] |
| Parse local pointer (UI) | `inboxParse.parseSourceRegion` → `.local` ✅ |
| Play/render local in review | `SourceSplicePanel` (`kind` off `local`, `mediaUrl` nginx) ✅ |
| Download target → resources | `download/downloader.py` / ingest download step [stage 2] |
| Capture→file path record | Java capture row `local_path` [stage 3] |
| Trash-when-unused sweep | new executor consuming `retention.RetentionPlan` [stage 4] |
| Retention policy | `ingest/retention.py` `compute_retention` (built) |
| PDF byte acquisition | extension classify + fetch (Drive = hard) [stage 5] |
