"""Keyframe selection: sloppy high-recall candidates → CLIP precision
(INGEST_AGENT_ARCH stage 2, keyframes). All local, zero LLM tokens.

Stage A  candidates: scene cuts (frame ~0.5s BEFORE the cut — a replaced slide
         is captured fully built) + 1 frame/15s safety net + transcript cue regex.
Stage B  CLIP zero-shot keep/drop via text prompts (cue frames bypass drop).
Stage C  CLIP cosine dedupe (keep the LATEST of a duplicate run = final slide
         state), cap MAX_FRAMES with cue frames prioritized.
"""
import base64
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path

log = logging.getLogger("embedder.ingest.keyframes")

SCENE_THRESHOLD = float(os.environ.get("SCENE_THRESHOLD", "27"))
PERIODIC_SAMPLE_S = float(os.environ.get("PERIODIC_SAMPLE_S", "15"))
CUE_OFFSET_S = 1.5
PRE_CUT_OFFSET_S = 0.5
CLIP_SIM_THRESHOLD = float(os.environ.get("CLIP_SIM_THRESHOLD", "0.92"))
MAX_FRAMES = int(os.environ.get("MAX_FRAMES", "40"))
CLIP_MODEL = os.environ.get("CLIP_MODEL", "ViT-L-14")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")

CUE_PATTERNS = re.compile(
    r"look at|as you can see|this diagram|this slide|on the board|shown here|"
    r"this chart|this graph|right here|this figure", re.IGNORECASE)

KEEP_PROMPTS = ["a presentation slide", "a diagram or chart",
                "a whiteboard with writing", "code on a computer screen",
                "a table of data"]
DROP_PROMPTS = ["a person talking to the camera", "an audience", "a room"]


def extract_keyframes(video_path: Path, segments: list[dict], slug: str) -> list[dict]:
    """Returns media entries [{path(name), loc:{t}, trigger, cue_text?, data_b64}]."""
    duration = _duration(video_path)
    candidates = {}   # timestamp → trigger info; cue wins over others

    for t in _scene_cut_times(video_path):
        candidates[round(max(0.0, t - PRE_CUT_OFFSET_S), 1)] = {"trigger": "scene"}
    t = PERIODIC_SAMPLE_S
    while t < duration:
        candidates.setdefault(round(t, 1), {"trigger": "periodic"})
        t += PERIODIC_SAMPLE_S
    for seg in segments:
        if CUE_PATTERNS.search(seg["text"]):
            ts = round(seg["loc"].get("t_start", 0) + CUE_OFFSET_S, 1)
            candidates[ts] = {"trigger": "cue", "cue_text": seg["text"][:160]}

    if not candidates:
        return []
    log.info("keyframes: %d candidates from %s", len(candidates), video_path.name)

    with tempfile.TemporaryDirectory() as tmp:
        frames = []
        for ts, info in sorted(candidates.items()):
            out = Path(tmp) / f"f{ts}.jpg"
            if _grab_frame(video_path, ts, out):
                frames.append({"t": ts, "file": out, **info})
        kept = _clip_filter_dedupe(frames)
        media = []
        for fr in kept:
            name = f"{slug}-{int(fr['t'])}.jpg"
            entry = {"path": name, "loc": {"t": fr["t"]},
                     "trigger": fr["trigger"],
                     "data_b64": base64.standard_b64encode(
                         fr["file"].read_bytes()).decode()}
            if fr.get("cue_text"):
                entry["cue_text"] = fr["cue_text"]
            media.append(entry)
        return media


# ── candidates ───────────────────────────────────────────────────────────

def _scene_cut_times(video_path: Path) -> list[float]:
    try:
        from scenedetect import detect, ContentDetector
        scenes = detect(str(video_path),
                        ContentDetector(threshold=SCENE_THRESHOLD))
        return [s[0].get_seconds() for s in scenes if s[0].get_seconds() > 0]
    except Exception as e:
        log.warning("scene detection failed (%s) — periodic sampler only", e)
        return []


def _duration(video_path: Path) -> float:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(video_path)],
        capture_output=True, text=True, timeout=60)
    try:
        return float(proc.stdout.strip())
    except ValueError:
        return 0.0


def _grab_frame(video_path: Path, ts: float, out: Path) -> bool:
    proc = subprocess.run(
        ["ffmpeg", "-y", "-ss", str(ts), "-i", str(video_path),
         "-frames:v", "1", "-q:v", "3", str(out)],
        capture_output=True, timeout=120)
    return proc.returncode == 0 and out.exists() and out.stat().st_size > 0


# ── CLIP filter + dedupe (sequential model policy: load → use → free) ────

def _load_clip():
    """Load the CLIP model on the best device. Shared by keyframe selection
    and PDF diagram filtering (extract_pdf) — same model, same prompts."""
    import open_clip
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model, _, preprocess = open_clip.create_model_and_transforms(
        CLIP_MODEL, pretrained="openai", cache_dir=MODEL_CACHE,
        precision="fp16" if device == "cuda" else "fp32")
    model = model.to(device).eval()
    tokenizer = open_clip.get_tokenizer(CLIP_MODEL)
    return model, preprocess, tokenizer, device


def diagram_keep_mask(images_png: list[bytes]) -> list[bool]:
    """True where the image reads as a diagram/chart/figure (vs a photo or
    decorative graphic), via CLIP zero-shot — the same KEEP/DROP prompts the
    keyframe selector uses. Best-effort: if CLIP is unavailable, keep all.

    Used by extract_pdf to drop logos/headshots while keeping real figures —
    the user's 'extract important diagrams' requirement.
    """
    if not images_png:
        return []
    try:
        import gc
        import io

        import torch
        from PIL import Image

        model, preprocess, tokenizer, device = _load_clip()
        with torch.no_grad():
            text_feat = model.encode_text(
                tokenizer(KEEP_PROMPTS + DROP_PROMPTS).to(device)).float()
            text_feat /= text_feat.norm(dim=-1, keepdim=True)
            mask = []
            for raw in images_png:
                img = preprocess(Image.open(io.BytesIO(raw)).convert("RGB")
                                 ).unsqueeze(0).to(device)
                f = model.encode_image(img).float()
                f /= f.norm(dim=-1, keepdim=True)
                sims = f.squeeze(0) @ text_feat.T
                keep = sims[:len(KEEP_PROMPTS)].max().item()
                drop = sims[len(KEEP_PROMPTS):].max().item()
                mask.append(keep >= drop)
        del model, text_feat
        gc.collect()
        if device == "cuda":
            torch.cuda.empty_cache()
        return mask
    except Exception as e:
        log.warning("CLIP diagram filter unavailable (%s) — keeping all", e)
        return [True] * len(images_png)


def _clip_filter_dedupe(frames: list[dict]) -> list[dict]:
    if not frames:
        return []
    try:
        import gc

        import torch
        from PIL import Image

        model, preprocess, tokenizer, device = _load_clip()

        with torch.no_grad():
            text_feat = model.encode_text(
                tokenizer(KEEP_PROMPTS + DROP_PROMPTS).to(device)).float()
            text_feat /= text_feat.norm(dim=-1, keepdim=True)

            feats = []
            for fr in frames:
                img = preprocess(Image.open(fr["file"])).unsqueeze(0).to(device)
                f = model.encode_image(img).float()
                feats.append((f / f.norm(dim=-1, keepdim=True)).squeeze(0))

            survivors = []
            for fr, feat in zip(frames, feats):
                sims = (feat @ text_feat.T)
                keep_score = sims[:len(KEEP_PROMPTS)].max().item()
                drop_score = sims[len(KEEP_PROMPTS):].max().item()
                if fr["trigger"] == "cue" or keep_score > drop_score:
                    survivors.append((fr, feat))

            # dedupe: within a duplicate run keep the LATEST frame
            deduped = []
            for fr, feat in survivors:                    # already time-sorted
                replaced = False
                for i, (kfr, kfeat) in enumerate(deduped):
                    if (feat @ kfeat).item() > CLIP_SIM_THRESHOLD:
                        if fr["trigger"] == "cue" or kfr["trigger"] != "cue":
                            deduped[i] = (fr, feat)       # later frame wins
                        replaced = True
                        break
                if not replaced:
                    deduped.append((fr, feat))

        del model, feats, text_feat
        gc.collect()
        if device == "cuda":
            torch.cuda.empty_cache()

        kept = [fr for fr, _ in deduped]
    except Exception as e:
        log.warning("CLIP unavailable (%s) — keeping cue+scene candidates raw", e)
        kept = [f for f in frames if f["trigger"] in ("cue", "scene")] or frames

    if len(kept) > MAX_FRAMES:
        cues = [f for f in kept if f["trigger"] == "cue"]
        rest = [f for f in kept if f["trigger"] != "cue"]
        kept = (cues + rest)[:MAX_FRAMES]
        kept.sort(key=lambda f: f["t"])
    return kept
