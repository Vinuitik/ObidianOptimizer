import logging
import os
from contextlib import asynccontextmanager
from typing import List

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException
from optimum.onnxruntime import ORTModelForFeatureExtraction
from pydantic import BaseModel
from transformers import AutoTokenizer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("embedder")

EMBED_MODEL = os.environ.get("EMBED_MODEL", "mixedbread-ai/mxbai-embed-large-v1")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")

# ---------------------------------------------------------------------------
# Shared state — populated in lifespan, read by endpoints
# ---------------------------------------------------------------------------
state: dict = {}


def _detect_provider() -> str:
    available = ort.get_available_providers()
    if "CUDAExecutionProvider" in available:
        log.info("GPU detected (CUDAExecutionProvider available) — using GPU inference.")
        return "CUDAExecutionProvider"
    log.warning("=" * 70)
    log.warning("WARN: No GPU / CUDAExecutionProvider detected.")
    log.warning("WARN: Falling back to CPU inference — embeddings will be slow.")
    log.warning("WARN: To fix: install nvidia-container-toolkit on the host,")
    log.warning("WARN: ensure Docker Desktop has 'Use GPU' enabled, and")
    log.warning("WARN: confirm the container has the 'deploy.resources' GPU block.")
    log.warning("=" * 70)
    return "CPUExecutionProvider"


def _load_model(provider: str):
    log.info("Loading model '%s' (cache: %s, provider: %s) …", EMBED_MODEL, MODEL_CACHE, provider)
    tokenizer = AutoTokenizer.from_pretrained(EMBED_MODEL, cache_dir=MODEL_CACHE)
    # export=True: if the Hub model has no ONNX files, optimum converts on the fly
    # and caches the result in MODEL_CACHE so it only happens once.
    model = ORTModelForFeatureExtraction.from_pretrained(
        EMBED_MODEL,
        cache_dir=MODEL_CACHE,
        export=True,
        provider=provider,
    )
    dim = model.config.hidden_size
    log.info("Model ready — dim=%d, provider=%s", dim, provider)
    return tokenizer, model, dim


@asynccontextmanager
async def lifespan(app: FastAPI):
    provider = _detect_provider()
    tokenizer, model, dim = _load_model(provider)
    state["tokenizer"] = tokenizer
    state["model"] = model
    state["dim"] = dim
    state["provider"] = provider
    state["model_name"] = EMBED_MODEL
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

    tokenizer = state["tokenizer"]
    model = state["model"]
    dim = state["dim"]

    encoded = tokenizer(
        req.texts,
        padding=True,
        truncation=True,
        max_length=512,
        return_tensors="np",
    )

    outputs = model(**encoded)
    token_embeddings = outputs.last_hidden_state          # (batch, seq, dim)
    attention_mask = encoded["attention_mask"]             # (batch, seq)

    # Mean pooling over non-padding tokens
    mask_expanded = attention_mask[:, :, np.newaxis].astype(np.float32)
    summed = np.sum(token_embeddings * mask_expanded, axis=1)
    counts = np.clip(mask_expanded.sum(axis=1), a_min=1e-9, a_max=None)
    embeddings = summed / counts                          # (batch, dim)

    # L2 normalise
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    embeddings = embeddings / np.clip(norms, a_min=1e-9, a_max=None)

    return EmbedResponse(
        embeddings=embeddings.tolist(),
        model=state["model_name"],
        dim=dim,
    )
