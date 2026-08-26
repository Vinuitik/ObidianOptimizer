import logging
import os
from contextlib import asynccontextmanager
from typing import List, Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import model_runtime
from model_runtime import state
from mcp_server import ApiKeyMiddleware, mcp

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("embedder")


@asynccontextmanager
async def lifespan(app: FastAPI):
    model_runtime.init()
    # The MCP streamable-HTTP session manager must run for /mcp to serve.
    async with mcp.session_manager.run():
        yield
    state.clear()


app = FastAPI(title="Embedder", lifespan=lifespan)

# Agent-escalation browser-tool bridge (/agent-ws) — extension connects here so the escalation
# agent can inspect the failed page's DOM/network and fetch with the user's session.
import agent_ws as _agent_ws
_agent_ws.register(app)


# ---------------------------------------------------------------------------
# API models
# ---------------------------------------------------------------------------

class EmbedRequest(BaseModel):
    texts: List[str]
    # 'document' = content being indexed; 'query' = a search query. Asymmetric
    # models (EmbeddingGemma) prefix the two differently — see model_runtime.
    kind: Literal["document", "query"] = "document"


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    model: str
    dim: int


class GenerateCardsRequest(BaseModel):
    note_path: str
    content: str | None = None      # omitted → read from the /vault mount
    source_hash: str | None = None  # omitted → sha256(content)
    # Feedback-aware regen (nightly refill of user-flagged cards): generate about
    # target_count fresh cards, steering the agent with the flag reasons so the
    # replacements avoid the same flaws. Omitted → normal full-set generation.
    target_count: int | None = None
    feedback: list[str] | None = None


class RollRequest(BaseModel):
    payload: dict                   # full exercise card payload
    condition: str | None = None    # named condition; omitted → random sample


class JudgeRequest(BaseModel):
    question: str
    answer: str
    reference_answers: list[str]
    key_points: list[str] | None = None


class PlacementGroupRequest(BaseModel):
    paths: List[str]                # absolute note paths — a source's notes, or one chapter's


class IngestRequest(BaseModel):
    ref: str = ""                   # /vault-relative path or URL (blank for text route)
    force_whisper: bool = False     # re-transcribe even if captions exist
    extract_only: bool = False      # stop after the bundle (skip synthesis)
    note_path: str | None = None    # in-place mode: host note holding the embed
    embed_ref: str | None = None    # the ![[…]] target to ingest below
    text: str | None = None         # text route: raw prose to ingest directly (no fetch)
    source_type: str | None = None  # capture source type (text|web_dom|…) for the bundle
    capture_id: str | None = None   # stamp into proposed notes so the Learn queue can group them
    title: str | None = None        # display title for the text route


class ResumeRequest(BaseModel):
    bundle_path: str                # the saved bundle to synthesize from (under BUNDLE_DIR)
    ref: str = ""                   # original source ref (for logging / note source)
    capture_id: str | None = None   # links proposed notes back to the Java capture row
    note_path: str | None = None    # in-place mode (else standalone)
    embed_ref: str | None = None
    source_type: str | None = None
    title: str | None = None


class SplitNoteRequest(BaseModel):
    note_path: str                  # vault-relative .md path


class MinicourseSubmitRequest(BaseModel):
    track_id: str


class MinicourseApproveRequest(BaseModel):
    approved_indexes: list[int] | None = None


class ImportCsvRequest(BaseModel):
    csv_text: str


class DownloadRequest(BaseModel):
    url: str                        # video / playlist URL for offline download


class SubsRequest(BaseModel):
    url: str                        # YouTube URL
    lang: str = "en"


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": state.get("model_name", "not loaded"),
        "dim": state.get("dim", 0),
        "device": "GPU" if state.get("provider") == "CUDAExecutionProvider" else "CPU",
    }


@app.get("/pdf-page")
def pdf_page(path: str, page: int = 1, dpi: int = 110, box: str | None = None, crop: int = 0):
    """Rasterize ONE page of a vault PDF → PNG for the review "source" panel, so a note's
    referenced page is visible inline (and a mis-sourced note — e.g. one the pipeline built
    from the table-of-contents page — is immediately obvious). The PDF is already served
    unauthenticated at /workspace + /vault-media, so this exposes nothing new; it's just a
    render of bytes the browser can already fetch.

    `path` is vault-relative (e.g. `_workspace/Introduction to Agents.pdf`); `page` is
    1-based; `dpi` is clamped 40–200. `box`="x0,y0,x1,y1" (PDF points — the SAME space the
    ingest bbox is stored in) draws a highlight rectangle on the page BEFORE rasterizing, so
    the frontend's "show region" toggle is just this param present/absent — no client-side
    coordinate math. Absent → the clean page.

    `crop=1` (needs `box`) renders ONLY the boxed region (+ a small margin) instead of the
    whole page with a highlight — so a chapter that starts/ends mid-page shows just its own
    content, not the confusing rest of the page. No highlight rectangle in crop mode: the crop
    IS the region."""
    from fastapi import Response
    from mcp_server import _resolve_in_vault
    import fitz

    try:
        pdf = _resolve_in_vault(path)
    except ValueError:
        raise HTTPException(status_code=400, detail="path escapes the vault")
    if pdf.suffix.lower() != ".pdf" or not pdf.exists():
        raise HTTPException(status_code=404, detail="not a PDF in the vault")

    dpi = max(40, min(int(dpi), 200))
    rect = _parse_box(box)
    try:
        doc = fitz.open(pdf)
        n_pages = doc.page_count
        pg = doc[max(1, min(int(page), n_pages)) - 1]   # clamp to a real page, 0-based
        clip = None
        if rect is not None:
            r = fitz.Rect(*rect)
            if crop:
                # Crop to JUST the sourced region (+ margin), clamped to the page. `clip` makes
                # get_pixmap rasterize only this sub-rect — the rest of the page never renders.
                pad = 8.0
                pr = pg.rect
                clip = fitz.Rect(max(pr.x0, r.x0 - pad), max(pr.y0, r.y0 - pad),
                                 min(pr.x1, r.x1 + pad), min(pr.y1, r.y1 + pad))
            else:
                # Stroked rect only (no fill) so the highlighted content stays readable. fitz
                # draw-space == text-block bbox space (top-left origin) → coords map directly.
                pg.draw_rect(r, color=(0.95, 0.25, 0.25), width=2.0)
        png = pg.get_pixmap(dpi=dpi, clip=clip).tobytes("png")
        doc.close()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"render failed: {e}")

    # A clean page is immutable per (file, page, dpi) → cache hard. A boxed render varies by
    # coords but is still deterministic; cache it too. `X-Pdf-Pages` lets the client clamp nav.
    return Response(content=png, media_type="image/png", headers={
        "Cache-Control": "public, max-age=604800",
        "X-Pdf-Pages": str(n_pages),
    })


def _parse_box(box: str | None):
    """"x0,y0,x1,y1" → (x0,y0,x1,y1) floats, or None if absent/malformed (→ clean page)."""
    if not box:
        return None
    try:
        parts = [float(v) for v in box.split(",")]
        return tuple(parts) if len(parts) == 4 else None
    except (ValueError, TypeError):
        return None


# On-device offline space is the constraint, not server CPU — so we transcode a lightweight
# rendition ONCE (server-side) and let the phone download that instead of the full lecture.
# No client-side (de)compression: the rendition is a normal, smaller, already-playable file.
_VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi"}
_AUDIO_EXTS = {".mp3", ".m4a", ".wav", ".ogg", ".flac"}
_RENDITION_DIR = "/reports/_renditions"        # writable, excluded from vault scans
_RENDITION_TIMEOUT_S = int(os.environ.get("RENDITION_TIMEOUT_S", "1200"))
_RENDITION_HEIGHT = os.environ.get("RENDITION_HEIGHT", "480")   # video rendition max height (px)


@app.get("/media-rendition")
def media_rendition(path: str, mode: str = "auto"):
    """A lightweight, already-playable rendition of a vault A/V file for offline download.
    Video → H.264 at ≤`RENDITION_HEIGHT`p (CRF 30) + AAC 64k, `+faststart`; audio (or
    `mode=audio` to strip a video's picture) → mono AAC 48k. Transcoded ONCE with ffmpeg and
    cached under `_RENDITION_DIR` keyed by (path, mtime, size, kind), so repeat pulls are
    instant. `path` is vault-relative; same unauth exposure as /vault-media (it's a re-encode
    of bytes already served there). The PWA warm stores this under the canonical media URL so
    the player is unchanged."""
    import hashlib
    import subprocess
    from pathlib import Path

    from fastapi.responses import FileResponse
    from mcp_server import _resolve_in_vault

    try:
        src = _resolve_in_vault(path)
    except ValueError:
        raise HTTPException(status_code=400, detail="path escapes the vault")
    ext = src.suffix.lower()
    if not src.exists() or ext not in (_VIDEO_EXTS | _AUDIO_EXTS):
        raise HTTPException(status_code=404, detail="not an A/V file in the vault")

    audio_only = mode == "audio" or ext in _AUDIO_EXTS
    st = src.stat()
    key = hashlib.sha256(
        f"{path}|{st.st_mtime_ns}|{st.st_size}|{'a' if audio_only else 'v' + _RENDITION_HEIGHT}|v1"
        .encode()).hexdigest()[:16]
    out_ext = ".m4a" if audio_only else ".mp4"
    cache_dir = Path(_RENDITION_DIR)
    cache_dir.mkdir(parents=True, exist_ok=True)
    out = cache_dir / f"{key}{out_ext}"

    if not out.exists():
        tmp = cache_dir / f".{key}.{os.getpid()}{out_ext}"   # temp+atomic-rename → concurrency-safe
        if audio_only:
            cmd = ["ffmpeg", "-y", "-i", str(src), "-vn", "-ac", "1",
                   "-c:a", "aac", "-b:a", "48k", "-movflags", "+faststart", str(tmp)]
        else:
            cmd = ["ffmpeg", "-y", "-i", str(src),
                   "-vf", f"scale=-2:min({_RENDITION_HEIGHT}\\,ih)",   # never upscale
                   "-c:v", "libx264", "-crf", "30", "-preset", "veryfast",
                   "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart", str(tmp)]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=_RENDITION_TIMEOUT_S)
            os.replace(tmp, out)
        except subprocess.TimeoutExpired:
            Path(tmp).unlink(missing_ok=True)
            raise HTTPException(status_code=504, detail="rendition timed out")
        except subprocess.CalledProcessError as e:
            Path(tmp).unlink(missing_ok=True)
            tail = (e.stderr or b"")[-300:].decode(errors="ignore")
            raise HTTPException(status_code=500, detail=f"transcode failed: {tail}")

    return FileResponse(out, media_type="audio/mp4" if audio_only else "video/mp4",
                        headers={"Cache-Control": "public, max-age=604800"})


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        raise HTTPException(status_code=422, detail="texts list cannot be empty")

    return EmbedResponse(
        embeddings=model_runtime.embed_texts(req.texts, kind=req.kind),
        model=state["model_name"],
        dim=state["dim"],
    )


@app.post("/flashcards/generate")
def generate_cards(req: GenerateCardsRequest):
    """Internal (backend → embedder): run the card-generation agent for one note.
    LLM calls route through the host-wrapper's claude CLI endpoint."""
    import hashlib

    from flashcards import generate as card_gen
    from mcp_server import _resolve_in_vault

    content = req.content
    if content is None:
        try:
            content = _resolve_in_vault(req.note_path).read_text(encoding="utf-8")
        except (ValueError, OSError) as e:
            raise HTTPException(status_code=404, detail=f"cannot read note: {e}")

    source_hash = req.source_hash or hashlib.sha256(content.encode("utf-8")).hexdigest()
    try:
        return card_gen.generate_for_note(req.note_path, content, source_hash,
                                          target_count=req.target_count, feedback=req.feedback)
    except card_gen.LLMUnavailable as e:
        # No LLM answered (wrapper down or all providers exhausted). Fail loud —
        # a 200 "0 cards" here would silently hide a missing/unreachable LLM.
        raise HTTPException(status_code=503, detail=f"LLM unavailable: {e}")


@app.post("/flashcards/roll")
def roll_exercise(req: RollRequest):
    """Roll an exercise variant: pick/sample params, render the template, and
    compute the expected answer NOW (frozen into the assignment by the backend
    so answer-time verification needs no further hops)."""
    import random

    from flashcards import validate as card_validate
    from flashcards.solver_sandbox import SandboxError, run_solver

    payload = req.payload
    try:
        if req.condition:
            match = next((c for c in payload.get("conditions", [])
                          if c.get("name") == req.condition), None)
            if match is None:
                raise HTTPException(status_code=422, detail=f"unknown condition: {req.condition}")
            params = match["values"]
        else:
            params = card_validate._sample_params(payload["params"], random.Random())
        rendered = payload["template"].format(**params)
        expected = run_solver(payload["solver"], params)
    except (SandboxError, KeyError, ValueError) as e:
        raise HTTPException(status_code=422, detail=f"roll failed: {e}")
    return {"params": params, "rendered": rendered, "expected": expected}


@app.post("/flashcards/judge")
def judge_answer(req: JudgeRequest):
    """Open-answer verification: banded cosine, LLM judge for the middle band."""
    from flashcards.judge import judge_open_answer

    return judge_open_answer(req.question, req.answer, req.reference_answers, req.key_points)


@app.post("/ingest")
def ingest_submit(req: IngestRequest):
    """Resource → notes pipeline (INGEST_AGENT_ARCH). Async: jobs are
    minutes-long (whisper).

    Two modes:
      • standalone — {ref}: extract → synthesize → create new note(s).
      • in-place   — {ref|embed_ref, note_path}: synthesize ONE block and
        inject it below the embed in note_path (the embed is kept; the
        chunker indexes the injected text). This is what the auto-scanner
        (ResourceScanService) fires for video/audio/PDF embeds in notes.
    """
    from ingest import jobs as ingest_jobs
    from ingest import router as ingest_router

    # Text route: the captured prose is the content — no ref to route or resolve.
    if req.text is not None and req.text.strip():
        return ingest_jobs.submit(
            req.ref or "", None, capture_id=req.capture_id, text=req.text,
            source_type=req.source_type or "text", title=req.title)

    in_place = bool(req.note_path)
    target = req.embed_ref or req.ref if in_place else req.ref

    try:
        ingest_router.route(target)    # fail fast on unroutable input
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))

    resolved = None
    if not target.startswith(("http://", "https://")):
        resolved = _resolve_embed(target)
        if resolved is None:
            raise HTTPException(status_code=404, detail=f"not in vault: {target}")

    if in_place:
        from mcp_server import _resolve_in_vault
        try:
            note = _resolve_in_vault(req.note_path)
        except (ValueError, OSError) as e:
            raise HTTPException(status_code=404, detail=f"bad note_path: {e}")
        if not note.is_file():
            raise HTTPException(status_code=404, detail=f"note not found: {req.note_path}")

    return ingest_jobs.submit(
        target, resolved, req.force_whisper, req.extract_only,
        note_path=req.note_path, embed_ref=(target if in_place else None),
        capture_id=req.capture_id, source_type=req.source_type, title=req.title)


def _resolve_embed(ref: str):
    """Resolve a vault embed to a file. Tries the path as written; if that
    misses (Obsidian embeds are usually bare basenames while the file lives
    under resources/…), searches the vault for a matching basename."""
    from mcp_server import VAULT_DIR, _resolve_in_vault

    try:
        p = _resolve_in_vault(ref)
        if p.exists():
            return p
    except (ValueError, OSError):
        pass
    base = ref.rsplit("/", 1)[-1]
    # Walk the vault pruning hidden dirs (.git, .obsidian, …) and _trash IN PLACE
    # so we never descend into them. rglob() did, which walked Git's huge object
    # store — wasted work AND triggered ENOMEM ([Errno 12]) on memory-tight hosts.
    from pathlib import Path
    for dirpath, dirnames, filenames in os.walk(VAULT_DIR):
        dirnames[:] = [d for d in dirnames
                       if not d.startswith(".") and d not in ("_trash", "_reports")]
        if base in filenames:
            candidate = Path(dirpath) / base
            if candidate.is_file():
                return candidate
    return None


@app.post("/ingest/resume")
def ingest_resume(req: ResumeRequest):
    """Retry a DEFERRED synthesis from its already-saved bundle — no re-extract.
    Called by the backend capture queue when LLM providers recover (durability:
    the bundle survives on disk, the 'this needs synthesis' state is a capture row)."""
    from ingest import jobs as ingest_jobs

    # path guard: only bundles under BUNDLE_DIR may be resumed
    bp = os.path.realpath(req.bundle_path)
    if not bp.startswith(os.path.realpath(str(ingest_jobs.BUNDLE_DIR)) + os.sep) \
            or not os.path.isfile(bp):
        raise HTTPException(status_code=404, detail="bundle not found")

    return ingest_jobs.submit(
        req.ref or "", None, resume_bundle=bp,
        note_path=req.note_path, embed_ref=req.embed_ref,
        capture_id=req.capture_id, source_type=req.source_type, title=req.title)


@app.post("/placement/group")
def placement_group(req: PlacementGroupRequest):
    """Folder suggestion for a GROUP of inbox notes (a whole source, or one PDF chapter
    subfolder) — same hierarchical centroid classifier as per-note find_home, just averaged
    over the group's own vectors first (`ingest.placement.suggest_group`). Called by
    InboxController when building the Learn queue tree; a folder is "symmetric" with a
    note here — same walk, just a group-mean vector instead of one note's."""
    from ingest import placement

    return {"suggestedFolder": placement.suggest_group(req.paths)}


@app.post("/ingest/split-note")
def split_note(req: SplitNoteRequest):
    """Break an oversized note into concept notes + a hub (synchronous —
    a few LLM calls, not a whisper job)."""
    from ingest import split_note as splitter
    from mcp_server import _resolve_in_vault

    try:
        content = _resolve_in_vault(req.note_path).read_text(encoding="utf-8")
    except (ValueError, OSError) as e:
        raise HTTPException(status_code=404, detail=f"cannot read note: {e}")
    try:
        return splitter.split(req.note_path, content)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))


@app.post("/tracks/minicourse")
def minicourse_submit(req: MinicourseSubmitRequest):
    """Kick off mini-course generation (tracks/FLOWS.md) for a Learning Track: fetch its
    title + attached items from Java's internal API, then hand off to the async
    outline job (tracks/minicourse_jobs.submit_outline). Poll GET /tracks/minicourse/{id}."""
    import httpx

    from ingest import publish
    from tracks import minicourse_jobs

    try:
        track = publish.get_track_items(req.track_id)
    except (publish.PublishError, httpx.HTTPError) as e:
        raise HTTPException(status_code=502, detail=f"track fetch failed: {e}")
    if track is None:
        raise HTTPException(status_code=404, detail=f"unknown track: {req.track_id}")

    return minicourse_jobs.submit_outline(req.track_id, track["title"], track["items"])


@app.post("/tracks/import")
def tracks_import(req: ImportCsvRequest):
    """Map an arbitrary spreadsheet export into {tracks, items} in one LLM call
    (tracks/import_agent.map_csv_to_tracks). Synchronous — no job registry, unlike
    /tracks/minicourse, since this is a single LLM-call-class of work."""
    from tracks import import_agent

    try:
        return import_agent.map_csv_to_tracks(req.csv_text)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))


@app.get("/tracks/minicourse/{job_id}")
def minicourse_status(job_id: str):
    from tracks import minicourse_jobs

    job = minicourse_jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job id")
    return job


@app.post("/tracks/minicourse/{job_id}/approve")
def minicourse_approve(job_id: str, req: MinicourseApproveRequest):
    from tracks import minicourse_jobs

    job = minicourse_jobs.approve(job_id, req.approved_indexes)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job id")
    return job


@app.get("/ingest/{job_id}")
def ingest_status(job_id: str):
    from ingest import jobs as ingest_jobs

    job = ingest_jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job id")
    return job


@app.get("/ingest")
def ingest_list():
    from ingest import jobs as ingest_jobs

    return {"jobs": ingest_jobs.list_jobs()}


# ---------------------------------------------------------------------------
# Download — offline media (yt-dlp), salvaged from the former VideoManager app.
# Async: a download/playlist is minutes-long. The browser extension reaches these
# via the Java backend proxy (CaptureController /download), since the embedder is
# loopback-only.
# ---------------------------------------------------------------------------

@app.post("/download")
def download_submit(req: DownloadRequest):
    from download import jobs as download_jobs

    url = (req.url or "").strip()
    if not url:
        raise HTTPException(status_code=422, detail="url cannot be empty")
    return download_jobs.submit(url)


@app.get("/download/{job_id}")
def download_status(job_id: str):
    from download import jobs as download_jobs

    job = download_jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job id")
    return job


@app.get("/download")
def download_list():
    from download import jobs as download_jobs

    return {"jobs": download_jobs.list_jobs()}


@app.post("/playlist/expand")
def playlist_expand(req: DownloadRequest):
    """List a playlist's videos (no download) so the Java capture endpoint can
    enqueue one durable capture row per video. See download/downloader.list_playlist_entries."""
    from download.downloader import list_playlist_entries

    url = (req.url or "").strip()
    if not url:
        raise HTTPException(status_code=422, detail="url cannot be empty")
    try:
        entries = list_playlist_entries(url)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    return {"entries": entries}


@app.post("/subs")
def subs_fetch(req: SubsRequest):
    """Captions only, no download — the ingest captions-fast-path, also exposed
    for parity/testing. Synchronous (a few seconds)."""
    from download import downloader

    try:
        return downloader.fetch_subs(req.url, req.lang)
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"subs unavailable: {e}")


# ---------------------------------------------------------------------------
# MCP — real Model Context Protocol endpoint at /mcp (see mcp_server.py).
# Mounted last so the routes above keep precedence. X-API-Key required.
# ---------------------------------------------------------------------------

app.mount("/", ApiKeyMiddleware(mcp.streamable_http_app(),
                                os.environ.get("MCP_API_TOKEN", "")))
