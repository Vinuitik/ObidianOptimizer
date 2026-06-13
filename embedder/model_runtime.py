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
from transformers import AutoTokenizer

log = logging.getLogger("embedder")

EMBED_MODEL = os.environ.get("EMBED_MODEL", "mixedbread-ai/mxbai-embed-large-v1")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
# Pre-exported ONNX weights live under onnx/ in the model repo. Override to point
# at a different file (e.g. onnx/model_fp16.onnx) or a fork that ships its own ONNX.
EMBED_ONNX_FILE = os.environ.get("EMBED_ONNX_FILE", "onnx/model.onnx")

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
    """Load tokenizer + ONNX session into shared state. Called once from the app lifespan.

    Pure onnxruntime — no torch, no optimum. The model ships a pre-exported ONNX
    on the Hub, so there is no PyTorch→ONNX conversion step. onnxruntime-gpu runs
    it on CUDA when available and falls back to the CPU provider otherwise.
    """
    from huggingface_hub import hf_hub_download
    from transformers import AutoConfig

    provider = detect_provider()
    # Always list the CPU provider as a fallback so a missing/partial CUDA stack
    # degrades to CPU rather than failing to create the session.
    providers = ([provider, "CPUExecutionProvider"]
                 if provider == "CUDAExecutionProvider" else ["CPUExecutionProvider"])
    log.info("Loading model '%s' (cache: %s, provider: %s) …", EMBED_MODEL, MODEL_CACHE, provider)

    tokenizer = AutoTokenizer.from_pretrained(EMBED_MODEL, cache_dir=MODEL_CACHE)
    onnx_path = hf_hub_download(EMBED_MODEL, EMBED_ONNX_FILE, cache_dir=MODEL_CACHE)
    session = ort.InferenceSession(onnx_path, providers=providers)
    dim = AutoConfig.from_pretrained(EMBED_MODEL, cache_dir=MODEL_CACHE).hidden_size

    active = session.get_providers()[0]
    log.info("Model ready — dim=%d, provider=%s", dim, active)
    state.update({
        "tokenizer": tokenizer,
        "session": session,
        "dim": dim,
        "provider": active,
        "model_name": EMBED_MODEL,
    })


def embed_texts(texts: List[str]) -> List[List[float]]:
    """Mean-pooled, L2-normalised embeddings. Raises KeyError if init() hasn't run."""
    tokenizer = state["tokenizer"]
    session = state["session"]

    encoded = tokenizer(
        texts,
        padding=True,
        truncation=True,
        max_length=512,
        return_tensors="np",
    )

    # Feed only the inputs this graph declares (BERT-based models also take
    # token_type_ids; some exports omit it), cast to the int64 ONNX expects.
    input_names = {i.name for i in session.get_inputs()}
    feeds = {k: np.asarray(v, dtype=np.int64)
             for k, v in encoded.items() if k in input_names}

    outputs = session.run(None, feeds)
    out_names = [o.name for o in session.get_outputs()]
    if "last_hidden_state" in out_names:
        token_embeddings = outputs[out_names.index("last_hidden_state")]
    else:                                                 # first 3-D output
        token_embeddings = next(a for a in outputs if a.ndim == 3)
    token_embeddings = token_embeddings.astype(np.float32)  # (batch, seq, dim)
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
