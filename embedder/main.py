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


# ---------------------------------------------------------------------------
# MCP — real Model Context Protocol endpoint at /mcp (see mcp_server.py).
# Mounted last so the routes above keep precedence. X-API-Key required.
# ---------------------------------------------------------------------------

app.mount("/", ApiKeyMiddleware(mcp.streamable_http_app(),
                                os.environ.get("MCP_API_TOKEN", "")))
