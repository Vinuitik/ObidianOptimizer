"""A/V extraction — transcript acquisition, cheapest source first.

Local files:   ffmpeg → 16kHz mono wav → faster-whisper → timestamped segments.
YouTube URLs:  captions via VideoManager subs endpoint (no download), parsed
               from VTT with rolling-window dedupe. Whisper fallback for
               YouTube requires the download path [NOT IMPLEMENTED in stage 1].

The whisper model loads lazily per job and is released afterwards — sequential,
one-model-at-a-time resource policy (INGEST_AGENT_ARCH deployment notes).
"""
import gc
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path

import requests

from ingest import router as ingest_router

log = logging.getLogger("embedder.ingest.av")

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "distil-large-v3")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
VIDEOMANAGER_URL = os.environ.get("VIDEOMANAGER_URL", "").rstrip("/")
FFMPEG_TIMEOUT_S = int(os.environ.get("FFMPEG_TIMEOUT_S", "1800"))


def extract(ref: str, resolved_path: Path | None, force_whisper: bool = False) -> dict:
    """Returns an Extraction Bundle dict (INGEST_AGENT_ARCH stage 3 contract)."""
    kind = ingest_router.route(ref)
    if kind == "youtube":
        if force_whisper:
            raise NotImplementedError(
                "FORCE_WHISPER for YouTube needs the VideoManager download path "
                "(stage 1 is captions-only for YouTube)")
        segments, title, duration = _youtube_captions(ref)
        source_type = "video"
    else:
        if resolved_path is None or not resolved_path.exists():
            raise FileNotFoundError(f"local A/V file not found: {ref}")
        segments, duration = _whisper_transcribe(resolved_path)
        title = resolved_path.stem
        source_type = "audio" if ingest_router.is_audio(ref) else "video"

    return {
        "source": {"type": source_type, "ref": ref, "title": title,
                   "duration_s": duration, "chapters": []},
        "segments": [
            {"loc": {"t_start": round(s["start"], 1), "t_end": round(s["end"], 1)},
             "text": s["text"].strip()}
            for s in segments if s["text"].strip()
        ],
        "media": [],   # keyframes are stage 4
    }


# ── local file path: ffmpeg + faster-whisper ─────────────────────────────

def _whisper_transcribe(path: Path):
    wav = _to_wav(path)
    try:
        from faster_whisper import WhisperModel
        device, compute = _pick_device()
        log.info("loading whisper %s on %s/%s", WHISPER_MODEL, device, compute)
        model = WhisperModel(WHISPER_MODEL, device=device, compute_type=compute,
                             download_root=MODEL_CACHE)
        try:
            seg_iter, info = model.transcribe(str(wav), vad_filter=True)
            segments = [{"start": s.start, "end": s.end, "text": s.text}
                        for s in seg_iter]
            return segments, round(info.duration, 1)
        finally:
            # sequential resource policy: free VRAM before the next stage runs
            del model
            gc.collect()
    finally:
        wav.unlink(missing_ok=True)


def _pick_device():
    """CUDA if ctranslate2 sees it, else CPU int8. Both paths are int8 —
    quality is identical, CPU is just slower (fine for overnight jobs)."""
    try:
        import ctranslate2
        if ctranslate2.get_cuda_device_count() > 0:
            return "cuda", "int8"
    except Exception as e:
        log.warning("ctranslate2 CUDA probe failed (%s) — CPU fallback", e)
    return "cpu", "int8"


def _to_wav(path: Path) -> Path:
    out = Path(tempfile.mkstemp(suffix=".wav")[1])
    cmd = ["ffmpeg", "-y", "-i", str(path), "-vn",
           "-ac", "1", "-ar", "16000", "-f", "wav", str(out)]
    proc = subprocess.run(cmd, capture_output=True, timeout=FFMPEG_TIMEOUT_S)
    if proc.returncode != 0:
        out.unlink(missing_ok=True)
        raise RuntimeError(
            f"ffmpeg failed ({proc.returncode}): {proc.stderr[-500:].decode(errors='replace')}")
    return out


# ── YouTube captions path (VideoManager, no download) ────────────────────

def _youtube_captions(url: str):
    if not VIDEOMANAGER_URL:
        raise RuntimeError(
            "VIDEOMANAGER_URL not configured — YouTube captions path needs the "
            "VideoManager container (INGEST_AGENT_ARCH decision 3)")
    res = requests.post(f"{VIDEOMANAGER_URL}/api/v1/subs",
                        json={"url": url}, timeout=120)
    if res.status_code != 200:
        raise RuntimeError(f"VideoManager /subs {res.status_code}: {res.text[:300]}")
    body = res.json()
    segments = parse_vtt(body["vtt"])
    if not segments:
        raise RuntimeError("no usable captions — re-run with the download+whisper "
                           "path once implemented")
    return segments, body.get("title", url), body.get("duration_s", 0)


# ── VTT parsing ──────────────────────────────────────────────────────────

_TS = re.compile(
    r"(?:(\d+):)?(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(?:(\d+):)?(\d{2}):(\d{2})\.(\d{3})")
_TAGS = re.compile(r"<[^>]+>")


def _ts_to_s(h, m, s, ms):
    return int(h or 0) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000.0


def parse_vtt(vtt: str):
    """Parse WebVTT to segments, deduping YouTube auto-sub rolling-window
    repeats (consecutive cues re-show the previous line)."""
    segments = []
    cur = None
    for line in vtt.splitlines():
        m = _TS.search(line)
        if m:
            if cur and cur["text"].strip():
                segments.append(cur)
            cur = {"start": _ts_to_s(*m.groups()[:4]),
                   "end": _ts_to_s(*m.groups()[4:]), "text": ""}
            continue
        if cur is None or not line.strip() or line.strip().isdigit() \
                or line.startswith(("WEBVTT", "Kind:", "Language:", "NOTE")):
            continue
        text = _TAGS.sub("", line).strip()
        if text:
            cur["text"] = (cur["text"] + " " + text).strip()
    if cur and cur["text"].strip():
        segments.append(cur)

    # rolling-window dedupe: drop a cue's text that repeats the previous tail
    deduped = []
    for seg in segments:
        if deduped:
            prev = deduped[-1]["text"]
            if seg["text"] == prev:
                deduped[-1]["end"] = seg["end"]
                continue
            if seg["text"].startswith(prev):
                seg = {**seg, "text": seg["text"][len(prev):].strip()}
                if not seg["text"]:
                    deduped[-1]["end"] = seg["end"]
                    continue
        deduped.append(dict(seg))
    return deduped
