"""Native A/V extraction → SourceIR (INGESTION_V2_FLOWS §8a).  [NEW / v2]

Audio is the base case; video = audio + an image track (keyframes stay in `keyframes.py`).
The **entire v2 skeleton is reused** — Block → Unit → locator_span → Draft → Retain — only
three things are audio-specific (§8): blocks come from the transcript (`type = speech`,
`locator = TimeSpan{start_ms,end_ms}`), the structural prior is **chapters** (not a TOC), and
the retention rule keeps the transcript (`retention.py` already does this for AUDIO/VIDEO).

**Split by testability** (mirrors `extract_ir` / `extract_pdf_ir`):
  - `build_ir(cues, chapters, ...)` — **pure**: Cue[] → SourceIR with SPEECH blocks + the
    A/V confidence flags (§8d). Unit-tested; no whisper, no yt-dlp, no GPU.
  - `from_bundle(v1_bundle)` — **pure**: a live v1 av bundle → native IR (SPEECH blocks +
    chapters TOC + AUTO_CAPTIONS) — richer than the generic `ir.ir_from_v1_bundle` bridge,
    and testable. This is the bridge replacement for A/V until `from_av` runs live.
  - `from_av(ref, path, force_whisper)` — I/O: reuses `extract_av`'s whisper/caption
    machinery (keeping whisper's `avg_logprob`/`no_speech_prob` this time) → `build_ir`.
    Needs faster-whisper/yt-dlp; not runnable in the sandbox.

A/V flags (§8d): ASR_LOW (HARD — low `avg_logprob`, transcription likely garbled),
AUTO_CAPTIONS (SOFT — auto-generated VTT, not uploaded), NON_SPEECH (SOFT — high
`no_speech_prob`, music/silence). NO_STRUCTURE (no chapters) and LONG_UNBROKEN come free
from `flagging.flag_source`. Diarization stays OFF (§8a) — a hook only.

*To tune ASR gating:* `INGEST_ASR_LOGPROB_MIN` / `INGEST_NO_SPEECH_MAX`.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from typing import Optional

from ingest.flagging import flag_source
from ingest.ir import (Block, BlockType, Flag, Medium, Severity, SourceIR,
                       TimeSpan, TocEntry)

log = logging.getLogger("embedder.ingest.av_ir")

# avg_logprob below this → ASR_LOW (HARD): whisper is unsure, transcription likely wrong.
ASR_LOGPROB_MIN = float(os.environ.get("INGEST_ASR_LOGPROB_MIN", "-1.0"))
# no_speech_prob above this → NON_SPEECH (SOFT): likely music / silence, not speech.
NO_SPEECH_MAX = float(os.environ.get("INGEST_NO_SPEECH_MAX", "0.6"))


# ── Whisper/VTT-independent input contract ────────────────────────────────────

@dataclass
class Cue:
    """One transcript utterance. `avg_logprob`/`no_speech_prob` are whisper's per-segment
    confidence (None for caption sources, which carry no such metadata)."""
    start_s: float
    end_s: float
    text: str
    avg_logprob: Optional[float] = None
    no_speech_prob: Optional[float] = None


# ── Pure core: Cue[] → SourceIR ───────────────────────────────────────────────

def build_ir(
    cues: list[Cue],
    chapters: Optional[list[dict]] = None,
    title: str = "Untitled",
    medium: Medium = Medium.VIDEO,
    auto_captions: bool = False,
    source_id: Optional[str] = None,
) -> SourceIR:
    """Transcript → positioned SPEECH blocks + A/V confidence flags. Pure."""
    blocks: list[Block] = []
    order = 0
    for c in cues:
        text = c.text.strip()
        if not text:
            continue
        blocks.append(Block(
            order_index=order, type=BlockType.SPEECH, text=text,
            locator=TimeSpan(start_ms=int(round(c.start_s * 1000)),
                             end_ms=int(round(c.end_s * 1000))),
            flags=_cue_flags(c)))
        order += 1

    # Auto-generated captions are a whole-source signal → tag the first block.
    if auto_captions and blocks:
        _add(blocks[0], "AUTO_CAPTIONS", Severity.SOFT, "auto-generated captions, not uploaded")

    # Chapters (yt-dlp / audiobook track) are the A/V structural prior → TOC (§8b).
    toc = [TocEntry(title=ch["title"], level=1) for ch in (chapters or []) if ch.get("title")]

    ir = SourceIR(medium=medium, title=title, blocks=blocks, toc=toc)
    if source_id:
        ir.source_id = source_id
    flag_source(ir)          # adds NO_STRUCTURE (no chapters) + LONG_UNBROKEN (§8d)
    return ir


def _cue_flags(c: Cue) -> list[Flag]:
    flags: list[Flag] = []
    if c.avg_logprob is not None and c.avg_logprob < ASR_LOGPROB_MIN:
        flags.append(Flag("ASR_LOW", Severity.HARD,
                          f"avg_logprob {c.avg_logprob:.2f} < {ASR_LOGPROB_MIN}"))
    if c.no_speech_prob is not None and c.no_speech_prob > NO_SPEECH_MAX:
        flags.append(Flag("NON_SPEECH", Severity.SOFT,
                          f"no_speech_prob {c.no_speech_prob:.2f} > {NO_SPEECH_MAX}"))
    return flags


def _add(block, code: str, severity: Severity, detail: str = "") -> None:
    if not any(f.code == code for f in block.flags):
        block.flags.append(Flag(code=code, severity=severity, detail=detail))


# ── Pure bridge: v1 av bundle → native IR (replaces ir_from_v1_bundle for A/V) ─

def from_bundle(bundle: dict) -> SourceIR:
    """A live v1 A/V bundle (`{source:{type,title,chapters}, segments:[{loc:{t_start,
    t_end}, text}]}`) → native IR with SPEECH blocks + chapters TOC. v1 segments carry no
    whisper confidence, so ASR/NON_SPEECH flags are absent here; `from_av` populates them."""
    src = bundle.get("source", {})
    medium = Medium.AUDIO if src.get("type") == "audio" else Medium.VIDEO
    cues = [Cue(start_s=float(s["loc"].get("t_start", 0)),
                end_s=float(s["loc"].get("t_end", s["loc"].get("t_start", 0))),
                text=s.get("text", ""))
            for s in bundle.get("segments", []) if "loc" in s]
    return build_ir(cues, chapters=src.get("chapters"), title=src.get("title") or "Untitled",
                    medium=medium)


# ── I/O layer: whisper/captions → Cue[] → build_ir (not sandbox-testable) ─────

def from_av(ref: str, resolved_path, force_whisper: bool = False) -> SourceIR:
    """Drive `extract_av`'s transcription (keeping whisper confidence this time) → build_ir.
    Reuses the v1 GPU-slot / device-fallback / caption machinery unchanged. Requires
    faster-whisper / yt-dlp; the pure `build_ir` + `from_bundle` are what tests cover."""
    from ingest import extract_av
    from ingest import router as ingest_router

    if ingest_router.route(ref) == "youtube" and not force_whisper:
        segs, title, _dur = extract_av._youtube_captions(ref)
        cues = [Cue(start_s=s["start"], end_s=s["end"], text=s["text"]) for s in segs]
        return build_ir(cues, title=title, medium=Medium.VIDEO, auto_captions=True)

    # whisper path — transcribe with metadata (v1 drops avg_logprob/no_speech_prob)
    if force_whisper and ingest_router.route(ref) == "youtube":
        from download import downloader
        audio = downloader.fetch_audio(ref)
        try:
            cues, title = _whisper_cues(audio), audio.stem
        finally:
            audio.unlink(missing_ok=True)
        medium = Medium.VIDEO
    else:
        from pathlib import Path
        path = Path(resolved_path)
        if not path.exists():
            raise FileNotFoundError(f"local A/V file not found: {ref}")
        cues, title = _whisper_cues(path), path.stem
        medium = Medium.AUDIO if ingest_router.is_audio(ref) else Medium.VIDEO

    return build_ir(cues, title=title, medium=medium)


def _whisper_cues(path) -> list[Cue]:
    """Transcribe keeping per-segment confidence. Mirrors `extract_av._whisper_transcribe`'s
    GPU-slot + CPU-fallback but retains `avg_logprob`/`no_speech_prob` for ASR flagging."""
    import gpu_slot
    from ingest import extract_av

    wav = extract_av._to_wav(path)
    try:
        for device, compute in extract_av._device_plan():
            try:
                with gpu_slot.exclusive("whisper"):
                    model = extract_av._load_whisper(device, compute)
                seg_iter, _info = model.transcribe(str(wav), vad_filter=True,
                                                   word_timestamps=True)
                return [Cue(start_s=s.start, end_s=s.end, text=s.text,
                            avg_logprob=getattr(s, "avg_logprob", None),
                            no_speech_prob=getattr(s, "no_speech_prob", None))
                        for s in seg_iter]
            except Exception as e:  # noqa: BLE001 — ANY GPU failure → CPU (as v1)
                if device == "cuda":
                    log.warning("whisper GPU failed (%s) — CPU fallback", str(e)[:120])
                    extract_av.unload_whisper()
                    continue
                raise
        raise RuntimeError("whisper transcription failed on all devices")
    finally:
        wav.unlink(missing_ok=True)
