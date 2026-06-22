import logging
import os
from contextlib import asynccontextmanager
from typing import List

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


# ---------------------------------------------------------------------------
# API models
# ---------------------------------------------------------------------------

class EmbedRequest(BaseModel):
    texts: List[str]


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    model: str
    dim: int


class GenerateCardsRequest(BaseModel):
    note_path: str
    content: str | None = None      # omitted → read from the /vault mount
    source_hash: str | None = None  # omitted → sha256(content)


class RollRequest(BaseModel):
    payload: dict                   # full exercise card payload
    condition: str | None = None    # named condition; omitted → random sample


class JudgeRequest(BaseModel):
    question: str
    answer: str
    reference_answers: list[str]
    key_points: list[str] | None = None


class IngestRequest(BaseModel):
    ref: str                        # /vault-relative path or URL
    force_whisper: bool = False     # re-transcribe even if captions exist
    extract_only: bool = False      # stop after the bundle (skip synthesis)
    note_path: str | None = None    # in-place mode: host note holding the embed
    embed_ref: str | None = None    # the ![[…]] target to ingest below


class SplitNoteRequest(BaseModel):
    note_path: str                  # vault-relative .md path


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


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        raise HTTPException(status_code=422, detail="texts list cannot be empty")

    return EmbedResponse(
        embeddings=model_runtime.embed_texts(req.texts),
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
        return card_gen.generate_for_note(req.note_path, content, source_hash)
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
        note_path=req.note_path, embed_ref=(target if in_place else None))


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
