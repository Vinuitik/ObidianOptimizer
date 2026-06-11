"""
Shared model runtime — owns the ONNX model, tokenizer, and embedding logic.

Both the FastAPI endpoints (main.py) and the MCP tools (mcp_server.py) import
from here, so neither needs to import the other.
"""
import logging
import os
from typing import List

import numpy as np
import onnxruntime as ort
from optimum.onnxruntime import ORTModelForFeatureExtraction
from transformers import AutoTokenizer

log = logging.getLogger("embedder")

EMBED_MODEL = os.environ.get("EMBED_MODEL", "mixedbread-ai/mxbai-embed-large-v1")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")

# Shared state — populated by init(), read by endpoints and MCP tools
state: dict = {}


def detect_provider() -> str:
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


def init() -> None:
    """Load model + tokenizer into shared state. Called once from the app lifespan."""
    provider = detect_provider()
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
    state.update({
        "tokenizer": tokenizer,
        "model": model,
        "dim": dim,
        "provider": provider,
        "model_name": EMBED_MODEL,
    })


def embed_texts(texts: List[str]) -> List[List[float]]:
    """Mean-pooled, L2-normalised embeddings. Raises KeyError if init() hasn't run."""
    tokenizer = state["tokenizer"]
    model = state["model"]

    encoded = tokenizer(
        texts,
        padding=True,
        truncation=True,
        max_length=512,
        return_tensors="np",
    )

    outputs = model(**encoded)
    token_embeddings = outputs.last_hidden_state          # (batch, seq, dim)
    attention_mask = encoded["attention_mask"]            # (batch, seq)

    # Mean pooling over non-padding tokens
    mask_expanded = attention_mask[:, :, np.newaxis].astype(np.float32)
    summed = np.sum(token_embeddings * mask_expanded, axis=1)
    counts = np.clip(mask_expanded.sum(axis=1), a_min=1e-9, a_max=None)
    embeddings = summed / counts                          # (batch, dim)

    # L2 normalise
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    embeddings = embeddings / np.clip(norms, a_min=1e-9, a_max=None)

    return embeddings.tolist()
