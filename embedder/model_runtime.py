"""
Shared model runtime — owns the ONNX text-embedding model, tokenizer, and the
GPU/CPU device policy.

Both the FastAPI endpoints (main.py) and the MCP tools (mcp_server.py) import
from here, so neither needs to import the other.

Device policy (4GB-card aware — see gpu_slot.py):
  • A **CPU session** is always built — the reliable floor.
  • A **GPU session** is built when CUDA truly engages, but the embedder does NOT
    own the GPU exclusively. Each embed call asks gpu_slot for the GPU: if it's
    free (or already the embedder's) it runs on GPU; if an ingest model (whisper/
    CLIP) holds the slot, it runs on the CPU session instead. So embeddings never
    block ingest and never OOM the card.
  • The GPU session is **capped** (gpu_mem_limit) and inputs are **sub-batched**
    (EMBED_BATCH_SIZE) so a big note can't balloon VRAM past the cap.
"""
import logging
import os
from typing import List

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

import gpu_slot

log = logging.getLogger("embedder")

# gte-base (768-dim, mean-pooled, standard BERT). Replaced mxbai-embed-large-v1
# (1024-dim, 335M): mxbai's fp16 ONNX crashed onnxruntime 1.19's SimplifiedLayerNormFusion
# at load, and mxbai is officially CLS-pooled — our embed path mean-pools, so its vectors
# were already slightly off. gte-base is mean-trained (so the mean-pool below is now
# *correct*), needs no query/doc prefix, and at 109M/~220MB fp16 frees ~450MB of VRAM
# for whisper/CLIP. Xenova/ mirror is used because it's the repo that ships model_fp16.onnx.
EMBED_MODEL = os.environ.get("EMBED_MODEL", "Xenova/gte-base")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
# fp16 by default: ~220MB vs the fp32 model.onnx's ~440MB — the headroom that lets a
# 4GB card hold the embedder OR whisper without OOM. fp16 vs fp32 shifts normalised
# vectors by ~1e-3 cosine (no re-embedding needed). Override to onnx/model.onnx for
# fp32, or onnx/model_quantized.onnx (int8) for even less VRAM.
EMBED_ONNX_FILE = os.environ.get("EMBED_ONNX_FILE", "onnx/model_fp16.onnx")
# Hard ceiling on the onnxruntime CUDA arena so the (otherwise unbounded, grow-only)
# activation scratch can't creep up and starve whisper. 0 = no cap.
EMBED_GPU_MEM_LIMIT_MB = int(os.environ.get("EMBED_GPU_MEM_LIMIT_MB", "1800"))
# Sub-batch size for a single session.run — bounds peak activation memory so one
# multi-chunk note can't exceed the arena cap. Pairs with the cap above.
EMBED_BATCH_SIZE = int(os.environ.get("EMBED_BATCH_SIZE", "16"))

# Shared state — populated by init(), read by endpoints and MCP tools.
# Keys: tokenizer, dim, model_name, provider, onnx_path, cpu_session, gpu_session.
state: dict = {}

# onnxruntime's EXTENDED-tier graph optimisation (SimplifiedLayerNormFusion) asserts
# on some fp16 ONNX exports — it's what crashed the old mxbai fp16 model at session
# build. BASIC keeps the cheap, safe optimisations (constant folding, redundant-cast
# removal) and skips the aggressive fp16 fusions, so a model swap can't re-trip the bug.
# Override with EMBED_ORT_OPT=all|extended|basic|disabled to chase max speed once a
# model is verified to load clean.
_ORT_OPT = {
    "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "disabled": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
}


def _session_options() -> ort.SessionOptions:
    so = ort.SessionOptions()
    so.graph_optimization_level = _ORT_OPT.get(
        os.environ.get("EMBED_ORT_OPT", "basic").lower(),
        ort.GraphOptimizationLevel.ORT_ENABLE_BASIC)
    return so


def detect_provider() -> str:
    available = ort.get_available_providers()
    if "CUDAExecutionProvider" in available:
        # NOTE: "available" only means the provider is compiled in — NOT that it
        # can actually initialise. If cuDNN/CUDA libs are missing it loads, then
        # fails at session creation and onnxruntime silently falls back to CPU.
        # init() verifies the *active* provider after the session is built.
        log.info("CUDAExecutionProvider is available — requesting GPU (verified after load).")
        return "CUDAExecutionProvider"
    log.warning("=" * 70)
    log.warning("WARN: No GPU / CUDAExecutionProvider detected.")
    log.warning("WARN: Falling back to CPU inference — embeddings will be slow.")
    log.warning("WARN: To fix: install nvidia-container-toolkit on the host,")
    log.warning("WARN: ensure Docker Desktop has 'Use GPU' enabled, and")
    log.warning("WARN: confirm the container has the 'deploy.resources' GPU block.")
    log.warning("=" * 70)
    return "CPUExecutionProvider"


def _cuda_providers() -> list:
    """CUDA provider + options (capped, request-sized arena) with a CPU fallback."""
    cuda_opts = {"arena_extend_strategy": "kSameAsRequested"}
    if EMBED_GPU_MEM_LIMIT_MB > 0:
        cuda_opts["gpu_mem_limit"] = EMBED_GPU_MEM_LIMIT_MB * 1024 * 1024
    return [("CUDAExecutionProvider", cuda_opts), "CPUExecutionProvider"]


def _build_gpu_session():
    """Build a (capped) CUDA session for the embedder. Returns the session, or None
    if CUDA didn't actually engage. Called at init AND lazily by gpu_slot when the
    embedder reclaims the GPU after an ingest model evicted it."""
    try:
        sess = ort.InferenceSession(
            state["onnx_path"], sess_options=_session_options(), providers=_cuda_providers())
    except Exception as e:  # noqa: BLE001 — a CUDA build failure must not crash embedding
        log.warning("GPU session build failed (%s) — embedder stays on CPU", e)
        return None
    if sess.get_providers()[0] != "CUDAExecutionProvider":
        log.warning("CUDA requested but session is on %s — treating as no-GPU",
                    sess.get_providers()[0])
        return None
    return sess


def init() -> None:
    """Load tokenizer + ONNX sessions into shared state. Called once from lifespan.

    Pure onnxruntime — no torch, no optimum. The model ships pre-exported ONNX on
    the Hub, so there's no PyTorch→ONNX conversion step.
    """
    from huggingface_hub import hf_hub_download
    from transformers import AutoConfig

    log.info("Loading model '%s' (cache: %s, onnx: %s) …",
             EMBED_MODEL, MODEL_CACHE, EMBED_ONNX_FILE)

    def _resolve(local_only: bool):
        # local_only=True reads the /models volume with ZERO network — no HF Hub
        # round-trips, no rate-limit, no stall.
        tokenizer = AutoTokenizer.from_pretrained(
            EMBED_MODEL, cache_dir=MODEL_CACHE, local_files_only=local_only)
        onnx_path = hf_hub_download(
            EMBED_MODEL, EMBED_ONNX_FILE, cache_dir=MODEL_CACHE, local_files_only=local_only)
        dim = AutoConfig.from_pretrained(
            EMBED_MODEL, cache_dir=MODEL_CACHE, local_files_only=local_only).hidden_size
        return tokenizer, onnx_path, dim

    try:
        tokenizer, onnx_path, dim = _resolve(local_only=True)
        log.info("Loaded tokenizer/config from local cache — no HF Hub requests.")
    except Exception as e:
        # Cache miss (first run / new fp16 file) — download once.
        log.info("Model not fully cached (%s) — downloading from HF Hub (one-time) …",
                 type(e).__name__)
        tokenizer, onnx_path, dim = _resolve(local_only=False)

    state.update(tokenizer=tokenizer, dim=dim, model_name=EMBED_MODEL, onnx_path=onnx_path)

    # CPU floor — always present so embedding works even while ingest owns the GPU.
    state["cpu_session"] = ort.InferenceSession(
        onnx_path, sess_options=_session_options(), providers=["CPUExecutionProvider"])
    state["gpu_session"] = None
    state["provider"] = "CPUExecutionProvider"

    if detect_provider() == "CUDAExecutionProvider":
        gpu_sess = _build_gpu_session()
        if gpu_sess is not None:
            state["gpu_session"] = gpu_sess
            state["provider"] = "CUDAExecutionProvider"
            gpu_slot.set_evictor("embedder", _unload_gpu_session)
            gpu_slot.set_gpu_available(True)
            log.info("Model ready — dim=%d, GPU session capped at %dMB, batch=%d",
                     dim, EMBED_GPU_MEM_LIMIT_MB, EMBED_BATCH_SIZE)
            return
        # CUDA was advertised but didn't engage — surface it loudly, run CPU-only.
        log.warning("=" * 70)
        log.warning("WARN: CUDA requested but the GPU session didn't engage —")
        log.warning("WARN: usually a cuDNN/CUDA mismatch (needs cuDNN 9 + CUDA 12).")
        log.warning("WARN: Running CPU-only. Check the onnxruntime error above.")
        log.warning("=" * 70)
    gpu_slot.set_gpu_available(False)
    log.info("Model ready — dim=%d, provider=CPU, batch=%d", dim, EMBED_BATCH_SIZE)


def _ensure_gpu_session():
    """Return the embedder's GPU session, rebuilding it if an ingest model evicted
    it. Called by gpu_slot under its lock, so it's single-threaded here."""
    if state.get("gpu_session") is None:
        state["gpu_session"] = _build_gpu_session()
    return state["gpu_session"]


def _unload_gpu_session() -> None:
    """Free the GPU session's VRAM so an ingest model can take the card. Registered
    with gpu_slot as the 'embedder' evictor; the CPU session stays as the floor."""
    if state.get("gpu_session") is not None:
        state["gpu_session"] = None
        import gc
        gc.collect()


def embed_texts(texts: List[str]) -> List[List[float]]:
    """Mean-pooled, L2-normalised embeddings, preserving input order.

    Picks GPU (via gpu_slot, if free) or the CPU floor per call, and sub-batches so
    a big input can't exceed the GPU arena cap. Raises KeyError if init() hasn't run.
    """
    if not texts:
        return []
    with gpu_slot.embedder_session(_ensure_gpu_session) as gpu_sess:
        session = gpu_sess if gpu_sess is not None else state["cpu_session"]
        out: List[List[float]] = []
        for i in range(0, len(texts), EMBED_BATCH_SIZE):
            out.extend(_embed_batch(session, texts[i:i + EMBED_BATCH_SIZE]))
        return out


def _embed_batch(session, texts: List[str]) -> List[List[float]]:
    """Embed one sub-batch on the given onnxruntime session."""
    tokenizer = state["tokenizer"]
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
    # fp16 model → fp16 output; upcast so pooling/normalisation is done in fp32.
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
