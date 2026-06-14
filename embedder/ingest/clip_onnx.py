"""Pure-ONNX CLIP (ViT-L/14) zero-shot encoder — no torch, no open_clip.

Runs on GPU via the onnxruntime CUDAExecutionProvider (the base image's CUDA,
shared with the text embedder) and falls back to CPU automatically. Pre-exported
ONNX weights are pulled from the Hub and cached in MODEL_CACHE on first use, so
there is no PyTorch→ONNX conversion step.

Exposes encode_text(prompts) / encode_image(pil_images) returning L2-normalised
feature matrices — callers do plain dot-products for cosine similarity. Sessions
are loaded once and cached (lazy), so the model is read from disk a single time.
"""
import logging
import os

import numpy as np

log = logging.getLogger("embedder.ingest.clip_onnx")

MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
# Repo that ships the pre-exported split ONNX graphs (text_model / vision_model).
CLIP_ONNX_REPO = os.environ.get("CLIP_ONNX_REPO", "Xenova/clip-vit-large-patch14")
# Repo for the processor (tokenizer + image preprocessing) — canonical OpenAI weights.
CLIP_HF_MODEL = os.environ.get("CLIP_HF_MODEL", "openai/clip-vit-large-patch14")

_state: dict = {}


def _providers() -> list[str]:
    import onnxruntime as ort
    avail = ort.get_available_providers()
    if "CUDAExecutionProvider" in avail:
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return ["CPUExecutionProvider"]


def _load() -> dict:
    """Lazy-load processor + the two ONNX sessions once; cached for reuse."""
    if _state:
        return _state
    import onnxruntime as ort
    from huggingface_hub import hf_hub_download
    from transformers import CLIPProcessor

    providers = _providers()
    processor = CLIPProcessor.from_pretrained(CLIP_HF_MODEL, cache_dir=MODEL_CACHE)
    vision_path = hf_hub_download(CLIP_ONNX_REPO, "onnx/vision_model.onnx", cache_dir=MODEL_CACHE)
    text_path = hf_hub_download(CLIP_ONNX_REPO, "onnx/text_model.onnx", cache_dir=MODEL_CACHE)
    vision = ort.InferenceSession(vision_path, providers=providers)
    text = ort.InferenceSession(text_path, providers=providers)
    log.info("CLIP ONNX loaded (providers=%s)", vision.get_providers())
    _state.update(processor=processor, vision=vision, text=text)
    return _state


def unload() -> None:
    """Release the CLIP sessions (~1.2GB VRAM/RAM). Safe to call whenever no
    ingest job is running — the next encode_*() call lazily reloads from the
    on-disk cache (a few seconds). The text embedder (model_runtime) stays hot;
    only this bursty, ingest-only model is evicted. Called by the ingest worker
    once its queue drains. Whisper already frees itself per job (extract_av)."""
    if not _state:
        return
    import gc
    _state.clear()
    gc.collect()
    log.info("CLIP ONNX unloaded — VRAM/RAM released until next ingest.")


def _l2(x: np.ndarray) -> np.ndarray:
    return x / np.clip(np.linalg.norm(x, axis=-1, keepdims=True), 1e-9, None)


def _feeds(session, enc: dict) -> dict:
    """Map encoder output to the session's declared inputs, casting ids to int64."""
    names = {i.name for i in session.get_inputs()}
    out = {}
    for k, v in enc.items():
        if k not in names:
            continue
        out[k] = np.asarray(v, dtype=np.int64) if k != "pixel_values" else np.asarray(v, dtype=np.float32)
    return out


def _embeds(session, outputs: list[np.ndarray]) -> np.ndarray:
    """Pick the projected embedding output (the WithProjection head), tolerant of
    export naming differences."""
    names = [o.name for o in session.get_outputs()]
    for pref in ("image_embeds", "text_embeds", "pooler_output", "sentence_embedding"):
        if pref in names:
            return outputs[names.index(pref)]
    for arr in outputs:                                   # fallback: first 2-D output
        if arr.ndim == 2:
            return arr
    return outputs[0]


def encode_text(prompts: list[str]) -> np.ndarray:
    """L2-normalised CLIP text features, shape (len(prompts), dim)."""
    s = _load()
    enc = s["processor"](text=prompts, return_tensors="np", padding=True, truncation=True)
    out = s["text"].run(None, _feeds(s["text"], enc))
    return _l2(_embeds(s["text"], out).astype(np.float32))


def encode_image(pil_images: list) -> np.ndarray:
    """L2-normalised CLIP image features, shape (len(images), dim)."""
    s = _load()
    enc = s["processor"](images=pil_images, return_tensors="np")
    out = s["vision"].run(None, _feeds(s["vision"], enc))
    return _l2(_embeds(s["vision"], out).astype(np.float32))
