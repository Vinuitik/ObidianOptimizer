"""
Single-occupant GPU arbiter — keeps the 4GB card from OOMing by guaranteeing that
**only one heavy model sits in VRAM at a time**.

Why this exists: the text embedder, faster-whisper, and CLIP each fit on the GPU
*alone* but not *together* on 4GB. Rather than probe free VRAM and gamble on
co-residency, we serialise: there is one "slot", and whoever wants the GPU evicts
whoever's in it first.

Two access patterns, reflecting the two kinds of GPU user:

  • exclusive(name)      — ingest models (whisper, CLIP). They BLOCK for the slot,
                           evict the current occupant, and become the occupant.
                           Background + latency-insensitive, so blocking is fine.
                           They stay loaded (lazy) until evicted or release_ingest().

  • embedder_session()   — the text embedder. It NEVER blocks and NEVER evicts an
                           ingest model: if the slot is free or already its own, it
                           runs on GPU; if an ingest model holds it, it gets None and
                           the caller falls back to a CPU session. This is the
                           "use the GPU if it's free, else CPU" policy — embeddings
                           on CPU are acceptable, whisper on CPU is painfully slow,
                           so ingest wins the GPU and the embedder bends.

The lock is held only across the short critical sections (claim / evict / a single
embed inference) — never for the minutes a whisper transcription runs (that happens
after exclusive() exits, with the occupant flag still set), so the embedder can keep
serving on CPU throughout.

No pynvml, no probing, no co-residency. Just a mutex + an occupant flag + per-model
evictors. Disable (force everything to behave as if no GPU) with GPU_SLOT=off.
"""
import logging
import os
import threading
from contextlib import contextmanager

log = logging.getLogger("embedder.gpu_slot")

ENABLED = os.environ.get("GPU_SLOT", "on").lower() != "off"

_lock = threading.Lock()
_occupant: str | None = None         # name of the model currently loaded in VRAM
_evictors: dict[str, callable] = {}  # name -> zero-arg callable that frees its VRAM
_gpu_available = False               # set by model_runtime once CUDA is confirmed

_INGEST = ("whisper", "clip")


def set_gpu_available(available: bool) -> None:
    """model_runtime calls this after it knows whether CUDA actually engaged."""
    global _gpu_available
    _gpu_available = bool(available)


def set_evictor(name: str, fn) -> None:
    """Register how to free a model's VRAM, so another claimant can evict it."""
    _evictors[name] = fn


def occupant() -> str | None:
    return _occupant


def _evict_locked(name: str) -> None:
    fn = _evictors.get(name)
    if not fn:
        return
    try:
        fn()
        log.info("[gpu_slot] evicted %s", name)
    except Exception as e:  # noqa: BLE001 — eviction must not wedge the slot
        log.warning("[gpu_slot] evict %s failed: %s", name, e)


@contextmanager
def exclusive(name: str):
    """Blocking claim for an ingest model. Evicts the prior occupant, becomes the
    occupant, then yields with the lock HELD — do only the model LOAD inside the
    `with`; run the long job *after* it (the occupant flag stays set, so the
    embedder keeps to CPU while you run). Free via release_ingest() when idle."""
    _lock.acquire()
    global _occupant
    try:
        if _occupant is not None and _occupant != name:
            _evict_locked(_occupant)
            _occupant = None
        _occupant = name
        yield
    finally:
        _lock.release()


@contextmanager
def embedder_session(load_fn):
    """Non-blocking GPU access for the text embedder.

    Yields the GPU session if the slot is free (or already the embedder's) —
    loading it via load_fn under the lock — and holds the lock for the duration of
    the `with` (one embed call: short). Yields None if an ingest model holds the
    slot, in which case the caller must use its CPU session. Never evicts ingest."""
    if not (ENABLED and _gpu_available):
        yield None
        return
    acquired = False
    sess = None
    if _lock.acquire(blocking=False):
        global _occupant
        if _occupant in (None, "embedder"):
            try:
                sess = load_fn()
                _occupant = "embedder"
                acquired = True
            except Exception as e:  # noqa: BLE001 — fall back to CPU on any GPU load error
                log.warning("[gpu_slot] embedder GPU load failed (%s) — CPU this call", e)
                sess = None
        if not acquired:
            _lock.release()
    try:
        yield sess
    finally:
        if acquired:
            _lock.release()


def release_ingest() -> None:
    """Drop whichever ingest model holds the slot (called when the ingest queue
    drains) so the embedder can reclaim the GPU. No-op if the embedder or nobody
    holds it."""
    global _occupant
    with _lock:
        if _occupant in _INGEST:
            _evict_locked(_occupant)
            _occupant = None
