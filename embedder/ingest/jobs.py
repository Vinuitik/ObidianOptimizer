"""Ingest job manager — async because jobs are minutes-long (whisper).

In-memory registry + single worker thread (MAX_CONCURRENT_JOBS=1: one model in
VRAM at a time, INGEST_AGENT_ARCH resource policy). Container restart kills
in-flight jobs — accepted in v1, jobs are idempotent and re-triggerable.

Finished bundles are persisted to {MODEL_CACHE}/ingest_bundles/{job_id}.json so
stage 2 synthesis can re-run without re-extracting.
"""
import json
import logging
import os
import queue
import re
import threading
import time
import uuid
from pathlib import Path

log = logging.getLogger("embedder.ingest.jobs")

BUNDLE_DIR = Path(os.environ.get("MODEL_CACHE", "/models")) / "ingest_bundles"

_jobs: dict[str, dict] = {}
_queue: "queue.Queue[str]" = queue.Queue()
_lock = threading.Lock()
_worker_started = False


def submit(ref: str, resolved_path, force_whisper: bool = False,
           extract_only: bool = False, note_path: str | None = None,
           embed_ref: str | None = None, capture_id: str | None = None,
           text: str | None = None, source_type: str | None = None,
           title: str | None = None) -> dict:
    # In-place dedup: never run two jobs for the same (note, embed) at once —
    # the auto-scanner re-fires on every save until the marker lands.
    if note_path and embed_ref:
        with _lock:
            for j in _jobs.values():
                if (j.get("note_path") == note_path
                        and j.get("embed_ref") == embed_ref
                        and j["status"] in ("QUEUED", "RUNNING")):
                    return public_view(j)
    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id, "ref": ref, "status": "QUEUED", "stage": None,
        "created_at": time.time(), "error": None, "bundle_path": None,
        "force_whisper": force_whisper, "extract_only": extract_only,
        "note_path": note_path, "embed_ref": embed_ref,
        "capture_id": capture_id, "source_type": source_type, "title": title,
        "_resolved_path": str(resolved_path) if resolved_path else None,
        "_text": text,
    }
    with _lock:
        _jobs[job_id] = job
    _ensure_worker()
    _queue.put(job_id)
    return public_view(job)


def get(job_id: str) -> dict | None:
    with _lock:
        job = _jobs.get(job_id)
        return public_view(job) if job else None


def list_jobs() -> list[dict]:
    with _lock:
        return [public_view(j) for j in
                sorted(_jobs.values(), key=lambda j: j["created_at"], reverse=True)]


def public_view(job: dict) -> dict:
    return {k: v for k, v in job.items() if not k.startswith("_")}


def _ensure_worker():
    global _worker_started
    with _lock:
        if _worker_started:
            return
        _worker_started = True
    threading.Thread(target=_worker_loop, daemon=True,
                     name="ingest-worker").start()


def _worker_loop():
    while True:
        job_id = _queue.get()
        with _lock:
            job = _jobs.get(job_id)
        if job is None:
            continue
        try:
            _run(job)
        except Exception as e:  # job errors must never kill the worker thread
            log.exception("ingest job %s failed", job_id)
            job["status"] = "FAILED"
            job["error"] = str(e)[:500]
        finally:
            # Once the burst drains, release the bursty ingest models so VRAM/RAM
            # returns to the rest of the stack. Deferred until the queue is empty
            # so a batch of jobs doesn't reload CLIP between each one.
            if _queue.empty():
                _evict_models()


def _evict_models():
    """Free whichever ingest model holds the GPU when no work remains (iceberg
    policy), via the single-occupant slot. release_ingest evicts whisper OR CLIP
    (whichever is the occupant) and frees the card for the text embedder to reclaim;
    it never touches the embedder itself."""
    try:
        import gpu_slot
        gpu_slot.release_ingest()
    except Exception as e:
        log.warning("model eviction skipped: %s", e)


def _run(job: dict):
    from ingest import extract_av, extract_pdf, extract_text, extract_web, router

    job["status"] = "RUNNING"
    resolved = Path(job["_resolved_path"]) if job["_resolved_path"] else None

    # Text route: the captured prose IS the content — no fetch, no router, no media.
    if job.get("_text") is not None:
        job["stage"] = "extract:text"
        bundle = extract_text.extract(
            job["_text"], job.get("title") or job.get("ref") or "Captured text",
            job.get("source_type") or "text", job.get("ref") or "")
    else:
        kind = router.route(job["ref"])
        # Many PDF URLs have no .pdf extension (arxiv /pdf/ID, CDN links) so the router calls
        # them 'web' → trafilatura fails on the binary. Sniff the content-type and reroute to
        # pdf so it downloads + extracts properly.
        if kind == "web" and job["ref"].startswith(("http://", "https://")) \
                and _sniff_is_pdf(job["ref"]):
            kind = "pdf"
        # PDF / direct-media URLs need a LOCAL file — extract_pdf/extract_av don't fetch URLs.
        # When the ref is an unresolved URL, download the bytes into resources/ FIRST so
        # extraction has a file and the note gets a local: copy (LOCAL_MEDIA_RETENTION §2c).
        # (youtube is separate: it transcribes from subtitles, no file needed here.)
        if resolved is None and kind in ("pdf", "av") \
                and job["ref"].startswith(("http://", "https://")):
            resolved = _prefetch_to_resources(job, kind)
        job["stage"] = f"extract:{kind}"
        if kind in ("av", "youtube"):
            bundle = extract_av.extract(job["ref"], resolved, job["force_whisper"])
            if kind == "av" and resolved and not router.is_audio(job["ref"]):
                job["stage"] = "keyframes"
                _attach_keyframes(job, bundle, resolved)
        elif kind == "pdf":
            bundle = extract_pdf.extract(job["ref"], resolved)
        elif kind == "web":
            bundle = extract_web.extract(job["ref"])
        else:
            raise NotImplementedError(
                f"route '{kind}': single images go through the existing "
                f"pending_image_jobs pipeline, not ingest")

    BUNDLE_DIR.mkdir(parents=True, exist_ok=True)
    out = BUNDLE_DIR / f"{job['id']}.json"
    out.write_text(json.dumps(bundle, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    job["bundle_path"] = str(out)
    job["segments"] = len(bundle["segments"])
    job["duration_s"] = bundle["source"]["duration_s"]
    job["stage"] = "extracted"

    if job.get("extract_only"):
        job["status"] = "DONE"
        return

    # LOCAL_MEDIA_RETENTION stage 2: persist a LOCAL copy of the source into the vault so
    # notes play the file itself (not an external embed). Best-effort — never fails the job.
    try:
        _ensure_local_copy(job, bundle, resolved)
    except Exception as e:
        log.warning("local media copy skipped for %s: %s", job.get("ref"), e)

    job["stage"] = "synthesize"
    # v2 cutover (INGEST_V2, default off): segment.py picks boundaries, the LLM only
    # writes each Unit. Same publish/capture path; v1 stays the default until flipped.
    from ingest import pipeline_v2
    v2 = pipeline_v2.v2_enabled()
    job["pipeline"] = "v2" if v2 else "v1"
    if job.get("note_path"):
        (_synthesize_and_inject_v2 if v2 else _synthesize_and_inject)(job, bundle)
    else:
        (_synthesize_and_publish_v2 if v2 else _synthesize_and_publish)(job, bundle)
    job["status"] = "DONE"
    log.info("ingest job %s: %d segments, %d note(s) from %s",
             job["id"], len(bundle["segments"]),
             len(job.get("notes_created", [])), job["ref"])


def _attach_keyframes(job: dict, bundle: dict, video_path: Path):
    """Keyframes are best-effort — a CLIP/scenedetect failure must not kill
    the transcript-based notes."""
    try:
        from ingest import keyframes
        slug = re.sub(r"[^\w-]", "-", video_path.stem)[:60]
        bundle["media"] = keyframes.extract_keyframes(
            video_path, bundle["segments"], slug)
    except Exception as e:
        log.warning("keyframes failed for %s (continuing without): %s",
                    job["ref"], e)


def _store_media(bundle: dict) -> set[str]:
    """Persist agent-produced media (keyframes, PDF figures) via the Java
    internal API so the ![[…]] embeds resolve. Returns the stored basenames."""
    from ingest import publish
    stored_names = set()
    for m in bundle.get("media", []):
        if "data_b64" in m:
            publish.store_media(m["path"], m.pop("data_b64"))
        stored_names.add(m["path"].rsplit("/", 1)[-1])
    return stored_names


def _vault_rel(p) -> str:
    """Container path → vault-relative path used in note bodies (mediaUrl resolves these):
    /resources/… → resources/… , /workspace/… → _workspace/… , /vault/… → …"""
    s = str(p)
    for prefix, rel in (("/resources/", "resources/"),
                        ("/workspace/", "_workspace/"),
                        ("/vault/", "")):
        if s.startswith(prefix):
            return rel + s[len(prefix):]
    return s


def _sniff_is_pdf(url: str) -> bool:
    """Is this URL actually a PDF, regardless of its (missing) extension? HEAD first, then a
    tiny ranged GET (magic bytes) as fallback. Best-effort — errors → False (stays 'web')."""
    import httpx
    try:
        r = httpx.head(url, follow_redirects=True, timeout=15.0)
        if "application/pdf" in r.headers.get("content-type", "").lower():
            return True
        if r.status_code >= 400:   # server dislikes HEAD → peek at the first bytes
            g = httpx.get(url, follow_redirects=True, timeout=15.0,
                          headers={"Range": "bytes=0-1023"})
            return ("application/pdf" in g.headers.get("content-type", "").lower()
                    or g.content[:5] == b"%PDF-")
    except Exception as e:
        log.debug("pdf sniff failed for %s: %s", url, e)
    return False


def _prefetch_to_resources(job: dict, kind: str) -> Path:
    """Download a PDF / direct-media URL into resources/<kind>/ so the extractor has a local
    file. Returns the local path (also becomes the note's local:). Raises on failure — for
    these types there's nothing to extract without the bytes (LOCAL_MEDIA_RETENTION §2c)."""
    import re as _re
    from urllib.parse import unquote, urlparse
    import httpx

    ref = job["ref"]
    sub = "pdf" if kind == "pdf" else "media"
    dest_dir = os.path.join(os.environ.get("RESOURCE_DIR", "/resources"), sub)
    os.makedirs(dest_dir, exist_ok=True)
    name = _re.sub(r"[^\w.\-]", "_", os.path.basename(unquote(urlparse(ref).path)))[:120] or "download"
    if kind == "pdf" and not name.lower().endswith(".pdf"):
        name += ".pdf"
    dest = os.path.join(dest_dir, name)
    job["stage"] = f"download:{sub}"
    with httpx.stream("GET", ref, follow_redirects=True, timeout=120.0) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_bytes():
                f.write(chunk)
    log.info("prefetched %s → %s", ref, dest)
    return Path(dest)


def _ensure_local_copy(job: dict, bundle: dict, resolved):
    """Persist a local copy of the source so notes play the file, not an external embed
    (LOCAL_MEDIA_RETENTION §2). Sets bundle['source']['local'] = vault-relative path.
      • YouTube/video-platform URL → download the full video into resources/media/.
      • A file already resolved in the vault (dropped/URL-saved) → just reference it.
    Text/web have no playable media → nothing to do. Blocking download runs on the ingest
    worker thread; the caller wraps this best-effort."""
    from ingest import router
    src = bundle.setdefault("source", {})
    if src.get("local"):
        return
    ref = job.get("ref") or ""
    if ref and router.route(ref) == "youtube":
        from download import downloader
        dest = os.path.join(os.environ.get("RESOURCE_DIR", "/resources"), "media")
        job["stage"] = "download:resources"
        path = downloader.download_sync(ref, dest_dir=dest)
        src["local"] = _vault_rel(path)
        # We now HAVE the video (transcription used subtitles only) → pull keyframes from it
        # so YouTube notes get diagrams/screenshots, not just transcript. Best-effort inside.
        if not bundle.get("media"):
            _attach_keyframes(job, bundle, Path(path))
    elif resolved is not None:
        src["local"] = _vault_rel(str(resolved))


def _synthesize_and_inject(job: dict, bundle: dict):
    """In-place: synthesize ONE block and inject it below the resource embed
    in the host note (the embed is kept; the chunker indexes the block)."""
    import hashlib

    from ingest import publish, synthesize
    from ingest import bundle as bundle_util
    from mcp_server import _resolve_in_vault

    stored_names = _store_media(bundle)
    plans = synthesize.outline(bundle)
    job["planned_notes"] = [p["title"] for p in plans]
    numbered = bundle_util.number_segments(bundle)
    block = synthesize.build_inplace_body(bundle, plans, numbered)

    problems = publish.validate_embeds(block, stored_names)
    if problems:
        raise publish.PublishError("; ".join(problems))

    resolved = Path(job["_resolved_path"]) if job["_resolved_path"] else None
    sha = hashlib.sha256(resolved.read_bytes()).hexdigest()[:16] if resolved else "live"
    content = _resolve_in_vault(job["note_path"]).read_text(encoding="utf-8")

    # The note is the Capture's source — not the embed — since a note can hold
    # multiple embeds and a Capture always has exactly one source. Snapshot the
    # pre-rewrite content before injecting so the Inbox can show what changed.
    capture_id = uuid.uuid4().hex[:12]
    publish.create_capture(capture_id, job["note_path"], content)

    new_content = publish.inject_block(content, job["embed_ref"], block, sha)
    new_content = publish.stamp_capture(new_content, capture_id, 1)
    publish.update_note(job["note_path"], new_content)
    job["notes_created"] = [job["note_path"]]
    job["capture_id"] = capture_id


# ── v2 synthesis (flagged) — deterministic boundaries, LLM writes only ────────

def _ir_from_bundle(bundle: dict):
    """A live v1 extraction bundle → SourceIR for the v2 pipeline. Native per-medium where
    it needs no re-reading of the source (A/V transcript → `extract_av_ir.from_bundle`);
    the generic `ir_from_v1_bundle` bridge for pdf/text (page/heading anchoring holds; real
    bbox/char precision arrives once extraction itself emits IR via `*_ir.from_*`)."""
    from ingest import extract_av_ir
    from ingest.ir import ir_from_v1_bundle
    stype = bundle.get("source", {}).get("type")
    if stype in ("audio", "video", "youtube"):
        return extract_av_ir.from_bundle(bundle)
    return ir_from_v1_bundle(bundle)


def _run_pipeline_v2(job: dict, bundle: dict):
    """IR → segment → draft each Unit (the ONLY LLM: `write_unit_body`) → retention/splice.
    Records the retention plan + review gate on the job (executor is a later step)."""
    from ingest import pipeline_v2, synthesize
    try:
        import model_runtime
        def embed_fn(texts):
            return model_runtime.embed_texts(texts, "document")
    except Exception as e:  # embedder missing → deterministic word-balance split
        log.warning("v2: embedder unavailable, deterministic split (%s)", e)
        embed_fn = None

    def draft_fn(unit, title):
        return synthesize.write_unit_body(title, unit.raw_text)

    ir = _ir_from_bundle(bundle)
    res = pipeline_v2.run(ir, draft_fn=draft_fn, embed_fn=embed_fn,
                          source_blob_path=bundle.get("source", {}).get("ref"))
    job["planned_notes"] = [n.title for n in res.notes]
    job["retention"] = res.retention.as_dict()   # observability; sweep is task 4
    job["needs_review"] = res.needs_review
    return res


def _synthesize_and_inject_v2(job: dict, bundle: dict):
    """v2 in-place: draft each Unit, assemble ONE block, inject below the embed — same
    capture/snapshot path as v1 `_synthesize_and_inject`."""
    import hashlib

    from ingest import publish, synthesize
    from mcp_server import _resolve_in_vault

    stored_names = _store_media(bundle)
    res = _run_pipeline_v2(job, bundle)
    drafted = [{"title": n.title, "body": n.body, "span": n.unit.locator_span}
               for n in res.notes]
    block = synthesize.build_inplace_body_v2(bundle["source"], drafted,
                                             bundle.get("media", []))

    problems = publish.validate_embeds(block, stored_names)
    if problems:
        raise publish.PublishError("; ".join(problems))

    resolved = Path(job["_resolved_path"]) if job["_resolved_path"] else None
    sha = hashlib.sha256(resolved.read_bytes()).hexdigest()[:16] if resolved else "live"
    content = _resolve_in_vault(job["note_path"]).read_text(encoding="utf-8")

    capture_id = uuid.uuid4().hex[:12]
    publish.create_capture(capture_id, job["note_path"], content)
    new_content = publish.inject_block(content, job["embed_ref"], block, sha)
    new_content = publish.stamp_capture(new_content, capture_id, 1)
    publish.update_note(job["note_path"], new_content)
    job["notes_created"] = [job["note_path"]]
    job["capture_id"] = capture_id


def _synthesize_and_publish_v2(job: dict, bundle: dict):
    """v2 standalone: one note per Unit into the Inbox staging folder. Reuses v1
    `synthesize.assemble` via a span→segs shim; one bad note never sinks its siblings."""
    from ingest import linking, publish, synthesize

    stored_names = _store_media(bundle)
    res = _run_pipeline_v2(job, bundle)

    suggested = publish.find_home(bundle["source"].get("title", ""))
    source_ref = bundle["source"].get("ref", "")
    publish.ensure_folder(publish.INBOX_FOLDER)
    capture_id = job.get("capture_id")
    mini_bundle = {"source": bundle["source"], "media": bundle.get("media", [])}
    # SEQUENTIAL links (§5): deterministic prev/next along order_index — res.notes is already
    # in order, so seq-1 / seq+1 ARE the chronological neighbours. We inject these, not the LLM.
    titles = [n.title for n in res.notes]
    sibling_stems = [synthesize.slugify(t) for t in titles]   # same-source, already SEQUENTIAL-linked
    created, failures = [], []
    for seq, n in enumerate(res.notes):
        try:
            segs = synthesize._span_to_segs(n.unit.locator_span)
            prev_title = titles[seq - 1] if seq > 0 else None
            next_title = titles[seq + 1] if seq < len(titles) - 1 else None
            chron = synthesize.chronology_block(prev_title, next_title)
            # SEMANTIC links (§5): ANN over the EXISTING vault index for the closest prior
            # notes (best-effort — no index / DB down → no links). Excludes self + siblings.
            related = linking.related_block(
                linking.related_links(n.body, exclude_stems=sibling_stems))
            # loop guard: an approved note is re-scanned; demote any A/V/PDF embed so it
            # never re-ingests the source (images stay embeds → still captioned). §9/loop.
            body = publish.demote_resource_embeds(n.body)
            if chron:
                body += "\n\n" + chron
            if related:
                body += "\n\n" + related
            note_md = synthesize.assemble(mini_bundle, {"title": n.title, "tags": []},
                                          segs, body)
            problems = publish.validate_note(note_md, stored_names)
            if problems:
                raise publish.PublishError("; ".join(problems))
            note_md = publish.stamp_inbox(note_md, source_ref, suggested)
            if capture_id:
                note_md = publish.stamp_capture(note_md, capture_id, seq)
            path = publish.create_note(publish.INBOX_FOLDER,
                                       synthesize.slugify(n.title), note_md)
            created.append(path)
        except Exception as e:  # one bad note must not sink its siblings
            log.warning("v2 note %r failed: %s", n.title, e)
            failures.append({"title": n.title, "error": str(e)[:300]})

    job["notes_created"] = created
    if failures:
        job["note_failures"] = failures
    if not created:
        raise RuntimeError(f"all {len(res.notes)} planned notes failed: {failures}")


def _synthesize_and_publish(job: dict, bundle: dict):
    from ingest import linking, publish, synthesize
    from ingest import bundle as bundle_util

    # store media first so embeds resolve at validation time
    stored_names = _store_media(bundle)

    plans = synthesize.outline(bundle)
    job["planned_notes"] = [p["title"] for p in plans]
    numbered = bundle_util.number_segments(bundle)

    # Notes land in the Inbox staging folder; find_home is only a SUGGESTED
    # destination stamped into each note for the triage UI to pre-pick.
    suggested = publish.find_home(bundle["source"].get("title", ""))
    source_ref = bundle["source"].get("ref", "")
    publish.ensure_folder(publish.INBOX_FOLDER)
    capture_id = job.get("capture_id")
    # Deterministic links are appended at the very end of each note (linking.py):
    #   CHRONO — every note links to the PREVIOUS one from this same source (first has none).
    #   SEMANTIC — LLM probes → hybrid search_notes → top-N existing notes.
    titles = [p["title"] for p in plans]
    sibling_stems = [synthesize.slugify(t) for t in titles]   # exclude self + same-source
    created, failures = [], []
    # enumerate → capture-seq: plans arrive in source order (outline windows run in
    # order), so seq preserves chapter/segment ordering for the Learn queue.
    for seq, plan in enumerate(plans):
        try:
            note_md = synthesize.write_note(bundle, plan, numbered)
            problems = publish.validate_note(note_md, stored_names)
            if problems:
                raise publish.PublishError("; ".join(problems))
            note_md = publish.stamp_inbox(note_md, source_ref, suggested)
            if capture_id:
                note_md = publish.stamp_capture(note_md, capture_id, seq)
            # Append links LAST so they sit at the very end of the note, verbatim.
            prev_stem = sibling_stems[seq - 1] if seq > 0 else None
            semantic = linking.semantic_link_stems(
                plan["title"], note_md, exclude_stems=sibling_stems)
            note_md = linking.append_links(note_md, ([prev_stem] if prev_stem else []) + semantic)
            path = publish.create_note(publish.INBOX_FOLDER,
                                       synthesize.slugify(plan["title"]), note_md)
            created.append(path)
        except Exception as e:  # one bad note must not sink its siblings
            log.warning("note %r failed: %s", plan["title"], e)
            failures.append({"title": plan["title"], "error": str(e)[:300]})

    job["notes_created"] = created
    if failures:
        job["note_failures"] = failures
    if not created:
        raise RuntimeError(f"all {len(plans)} planned notes failed: {failures}")
