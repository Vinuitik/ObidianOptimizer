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
    return card_gen.generate_for_note(req.note_path, content, source_hash)


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


# ---------------------------------------------------------------------------
# MCP — real Model Context Protocol endpoint at /mcp (see mcp_server.py).
# Mounted last so the routes above keep precedence. X-API-Key required.
# ---------------------------------------------------------------------------

app.mount("/", ApiKeyMiddleware(mcp.streamable_http_app(),
                                os.environ.get("MCP_API_TOKEN", "")))
