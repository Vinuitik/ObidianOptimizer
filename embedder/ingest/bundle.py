"""Bundle utilities — deterministic chunking along segment boundaries.

The LLM never sees raw files; it sees windows of numbered segments from the
Extraction Bundle (INGEST_AGENT_ARCH stage 3 contract).
"""
import os

WINDOW_TOKENS = int(os.environ.get("INGEST_WINDOW_TOKENS", "4000"))
_CHARS_PER_TOKEN = 4  # cheap, conservative estimate — no tokenizer dependency


def number_segments(bundle: dict) -> list[dict]:
    """Stable ids: segment index in bundle order."""
    return [{"id": i, **seg} for i, seg in enumerate(bundle["segments"])]


def windows(numbered_segments: list[dict]) -> list[list[dict]]:
    """Split into ~WINDOW_TOKENS windows, never breaking inside a segment."""
    limit = WINDOW_TOKENS * _CHARS_PER_TOKEN
    out, cur, cur_len = [], [], 0
    for seg in numbered_segments:
        seg_len = len(seg["text"])
        if cur and cur_len + seg_len > limit:
            out.append(cur)
            cur, cur_len = [], 0
        cur.append(seg)
        cur_len += seg_len
    if cur:
        out.append(cur)
    return out


def render_segments(segs: list[dict]) -> str:
    """[id @ loc] text — the shape both synthesis prompts consume."""
    lines = []
    for seg in segs:
        loc = seg.get("loc", {})
        if "t_start" in loc:
            tag = f"{_fmt_ts(loc['t_start'])}-{_fmt_ts(loc.get('t_end', loc['t_start']))}"
        elif "page" in loc:
            tag = f"p.{loc['page']}"
        else:
            tag = loc.get("heading", "")
        lines.append(f"[{seg['id']} @ {tag}] {seg['text']}")
    return "\n".join(lines)


def _fmt_ts(seconds: float) -> str:
    s = int(seconds)
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}" if s >= 3600 \
        else f"{s // 60}:{s % 60:02d}"
