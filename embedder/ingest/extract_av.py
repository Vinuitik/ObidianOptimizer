"""A/V extraction — transcript acquisition, cheapest source first.

Local files:   ffmpeg → 16kHz mono wav → faster-whisper → timestamped segments.
YouTube URLs:  captions via the in-process yt-dlp downloader (no download), parsed
               from VTT with rolling-window dedupe. force_whisper downloads the
               audio with yt-dlp and runs whisper (more accurate, slower).

The downloader was salvaged from the former VideoManager sister app into
download/downloader.py, so this no longer makes an HTTP hop to a separate service.

Whisper claims the GPU via the single-occupant slot (gpu_slot.exclusive), evicting
the text embedder; it's cached across jobs and freed when the ingest queue drains.
See ../GPU_MEMORY.md for the one-model-at-a-time VRAM policy.
"""
import gc
import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path

import gpu_slot
from ingest import router as ingest_router

log = logging.getLogger("embedder.ingest.av")

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "distil-large-v3")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
FFMPEG_TIMEOUT_S = int(os.environ.get("FFMPEG_TIMEOUT_S", "1800"))

# Whisper is cached across jobs (not reloaded per file) so an ingest burst doesn't
# thrash the embedder in and out of the GPU between transcriptions. It's freed when
# the ingest queue drains (jobs._evict_models → gpu_slot.release_ingest).
_whisper: dict = {}   # {"model": WhisperModel, "device": "cuda"|"cpu"}


def unload_whisper() -> None:
    """Free the whisper model's VRAM/RAM. Registered as the gpu_slot 'whisper'
    evictor so the embedder (or CLIP) can reclaim the card."""
    if _whisper.get("model") is not None:
        _whisper.clear()
        gc.collect()
        log.info("whisper unloaded — VRAM/RAM released until next transcription.")


gpu_slot.set_evictor("whisper", unload_whisper)


def _load_whisper(device: str, compute: str):
    """(Re)build the whisper model on `device`, reusing the cached one if it already
    matches. Call inside gpu_slot.exclusive('whisper') so the embedder is evicted
    first and the load has the VRAM it needs."""
    if _whisper.get("model") is not None and _whisper.get("device") == device:
        return _whisper["model"]
    unload_whisper()
    from faster_whisper import WhisperModel
    log.info("loading whisper %s on %s/%s", WHISPER_MODEL, device, compute)
    model = WhisperModel(WHISPER_MODEL, device=device, compute_type=compute,
                         download_root=MODEL_CACHE)
    _whisper.update(model=model, device=device)
    return model


def _is_cuda_oom(e: Exception) -> bool:
    msg = str(e).lower()
    return "out of memory" in msg or "cuda failed" in msg


def extract(ref: str, resolved_path: Path | None, force_whisper: bool = False) -> dict:
    """Returns an Extraction Bundle dict (INGEST_AGENT_ARCH stage 3 contract)."""
    kind = ingest_router.route(ref)
    if kind == "youtube":
        if force_whisper:
            segments, title, duration = _youtube_whisper(ref)
        else:
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

def _run_transcribe(model, wav: Path):
    seg_iter, info = model.transcribe(str(wav), vad_filter=True)
    segments = [{"start": s.start, "end": s.end, "text": s.text} for s in seg_iter]
    return segments, round(info.duration, 1)


def _whisper_transcribe(path: Path):
    """Transcribe via the single-occupant GPU slot: claim the GPU (evicting the
    text embedder), load whisper, transcribe. On ANY CUDA failure (OOM, missing
    libcublas/cudnn, driver mismatch, load OR transcribe error) retry the whole thing
    on CPU — resilience over speed — logging a loud WARN banner so the downgrade is
    visible. The slot is held only across the model LOAD; transcription runs without
    it, so the embedder keeps serving on its CPU floor meanwhile."""
    wav = _to_wav(path)
    try:
        for device, compute in _device_plan():
            try:
                with gpu_slot.exclusive("whisper"):
                    model = _load_whisper(device, compute)
                return _run_transcribe(model, wav)
            except Exception as e:  # noqa: BLE001 — resilience: ANY GPU failure → CPU
                # Degrade to CPU on *every* CUDA failure, not just OOM: missing
                # libcublas/cudnn, driver mismatch, init errors, etc. all land here.
                # SCREAM so a silent CPU downgrade is visible in logs and never mistaken
                # for "GPU is working" — transcription still succeeds, just slower.
                if device == "cuda":
                    reason = "OOM" if _is_cuda_oom(e) else "CUDA/library error"
                    log.warning("=" * 70)
                    log.warning("WARN: whisper FAILED on GPU (%s): %s", reason, str(e)[:140])
                    log.warning("WARN: FALLING BACK TO CPU — transcription will be SLOWER.")
                    log.warning("WARN: fix the GPU path to restore speed; ingest is NOT broken.")
                    log.warning("=" * 70)
                    unload_whisper()
                    continue
                raise  # CPU itself failed — nothing left to fall back to.
        raise RuntimeError("whisper transcription failed on all devices")
    finally:
        wav.unlink(missing_ok=True)


def _device_plan() -> list[tuple[str, str]]:
    """Devices to try in order. CUDA first (whisper has the card to itself once the
    slot evicts the embedder), CPU as the OOM fallback."""
    device, compute = _pick_device()
    return [(device, compute), ("cpu", "int8")] if device == "cuda" else [("cpu", "int8")]


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


# ── YouTube captions path (in-process yt-dlp, no download) ────────────────

def _youtube_captions(url: str):
    from download import downloader

    try:
        body = downloader.fetch_subs(url)
    except Exception as e:
        raise RuntimeError(
            f"caption fetch failed ({e}); re-run with force_whisper for the "
            f"download+whisper path") from e
    segments = parse_vtt(body["vtt"])
    if not segments:
        raise RuntimeError("no usable captions — re-run with force_whisper "
                           "for the download+whisper path")
    return segments, body.get("title", url), body.get("duration_s", 0)


# ── YouTube whisper path (yt-dlp audio download → faster-whisper) ─────────

def _youtube_whisper(url: str):
    """More accurate than captions: download bestaudio with yt-dlp, transcribe.
    The temp audio file is removed after transcription."""
    from download import downloader

    audio = downloader.fetch_audio(url)
    try:
        segments, duration = _whisper_transcribe(audio)
    finally:
        try:
            audio.unlink(missing_ok=True)
        except OSError:
            pass
    return segments, audio.stem, duration


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
