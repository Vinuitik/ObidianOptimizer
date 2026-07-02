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

# EmbeddingGemma-300m (768-dim out, asymmetric prompts, QAT int8). Replaced gte-base
# (109M, symmetric, English-only): gte-base's cosine band was so compressed
# (nonsense floored at ~0.87, exact matches ~0.91) that semantic scores carried
# almost no signal, and the vault has Russian notes it couldn't represent at all.
# EmbeddingGemma is the top multilingual model <500M on MTEB (en v2 69.7 / mml 61.2),
# and measured here: relevant 0.55, irrelevant 0.03, ru-relevant 0.46 — a real band.
# Same 768 dims as gte-base, so pgvector schema is untouched (chunks DO need
# re-embedding: vectors from different models share nothing).
# The onnx-community export bakes the FULL sentence-transformers pipeline into the
# graph (mean pool → dense 768→3072→768 → L2 norm) and exposes it as the 2-D
# 'sentence_embedding' output — pooling last_hidden_state ourselves would skip the
# dense projections and produce garbage. _embed_batch prefers the 2-D output.
EMBED_MODEL = os.environ.get("EMBED_MODEL", "onnx-community/embeddinggemma-300m-ONNX")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
# int8 (QAT — near-lossless for this model) by default: ~310MB, CPU-friendly, and
# small enough beside whisper/CLIP on the 4GB card. onnx/model_fp16.onnx (~620MB)
# if a GPU-heavy setup wants max fidelity.
EMBED_ONNX_FILE = os.environ.get("EMBED_ONNX_FILE", "onnx/model_quantized.onnx")

# EmbeddingGemma is prompt-asymmetric: queries and documents get different task
# prefixes (same weights — the prompt conditions the space so questions land near
# texts that ANSWER them, not texts phrased like them). Symmetric models keep both
# prefixes empty. Env-overridable for model swaps.
_IS_GEMMA = "embeddinggemma" in EMBED_MODEL.lower()
EMBED_QUERY_PREFIX = os.environ.get(
    "EMBED_QUERY_PREFIX", "task: search result | query: " if _IS_GEMMA else "")
EMBED_DOC_PREFIX = os.environ.get(
    "EMBED_DOC_PREFIX", "title: none | text: " if _IS_GEMMA else "")
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
        # External-data exports (embeddinggemma et al.) keep weights in a sibling
        # '<file>.onnx_data' — the session load fails without it. Best-effort: not
        # every repo splits its weights out.
        try:
            hf_hub_download(EMBED_MODEL, EMBED_ONNX_FILE + "_data",
                            cache_dir=MODEL_CACHE, local_files_only=local_only)
        except Exception:  # noqa: BLE001 — single-file exports have no _data companion
            pass
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


def embed_texts(texts: List[str], kind: str = "document") -> List[List[float]]:
    """L2-normalised embeddings, preserving input order.

    kind='document' embeds content to be retrieved; kind='query' embeds a search
    query. Prompt-asymmetric models (EmbeddingGemma) map the two differently —
    mixing them up silently degrades retrieval, so every caller must say which
    side it is. Symmetric models ignore the distinction (both prefixes empty).

    Picks GPU (via gpu_slot, if free) or the CPU floor per call, and sub-batches so
    a big input can't exceed the GPU arena cap. Raises KeyError if init() hasn't run.
    """
    if not texts:
        return []
    prefix = EMBED_QUERY_PREFIX if kind == "query" else EMBED_DOC_PREFIX
    texts = [prefix + t for t in texts] if prefix else list(texts)
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
    if "sentence_embedding" in out_names:
        # Sentence-transformers-style export: pooling + dense projections + norm
        # are IN the graph. Pooling last_hidden_state ourselves would skip the
        # dense layers (embeddinggemma: 768→3072→768) and produce garbage.
        embeddings = outputs[out_names.index("sentence_embedding")].astype(np.float32)
    else:
        if "last_hidden_state" in out_names:
            token_embeddings = outputs[out_names.index("last_hidden_state")]
        else:                                             # first 3-D output
            token_embeddings = next(a for a in outputs if a.ndim == 3)
        # fp16 model → fp16 output; upcast so pooling/normalisation is done in fp32.
        token_embeddings = token_embeddings.astype(np.float32)  # (batch, seq, dim)
        attention_mask = encoded["attention_mask"]        # (batch, seq)

        # Mean pooling over non-padding tokens
        mask_expanded = attention_mask[:, :, np.newaxis].astype(np.float32)
        summed = np.sum(token_embeddings * mask_expanded, axis=1)
        counts = np.clip(mask_expanded.sum(axis=1), a_min=1e-9, a_max=None)
        embeddings = summed / counts                      # (batch, dim)

    # L2 normalise
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    embeddings = embeddings / np.clip(norms, a_min=1e-9, a_max=None)

    return embeddings.tolist()
