"""Text extraction — the user's own captured prose (paste / selection / DOM article).

No fetch, no LLM: the text IS the content. We just segment it deterministically so
synthesis sees numbered, ordered segments like every other extractor (CAPTURE_ARCH.md
stage 0). Markdown with headings → one segment per section (order preserved); plain
text → paragraph windows so a long paste isn't one undifferentiated blob.
"""
import logging
import os
import re

log = logging.getLogger("embedder.ingest.text")

MIN_CONTENT_CHARS = 1
_HEADING = re.compile(r"^#{1,4}\s+(.+)$", re.MULTILINE)
# Group plain-text paragraphs into ~this many chars per segment so headingless
# pastes still chunk along real boundaries instead of becoming one segment.
PARA_SEGMENT_CHARS = int(os.environ.get("INGEST_TEXT_SEGMENT_CHARS", "1500"))


def extract(text: str, title: str, source_type: str = "text",
            source_ref: str = "") -> dict:
    body = (text or "").strip()
    if len(body) < MIN_CONTENT_CHARS:
        raise RuntimeError("empty text — nothing to ingest")

    segments = _split_by_heading(body)
    # chapters = the ordered heading list — synthesize.outline uses this to keep
    # note boundaries on real chapter lines rather than mid-section.
    chapters = [{"title": s["loc"]["heading"]}
                for s in segments if s["loc"].get("heading")]

    return {
        "source": {"type": source_type, "ref": source_ref, "title": title or "Captured text",
                   "duration_s": 0, "chapters": chapters},
        "segments": segments,
        "media": [],
    }


def _split_by_heading(text: str) -> list[dict]:
    """One segment per heading section (preamble first); headingless text falls
    back to paragraph windows. Segment order == source order (the ids that
    bundle.number_segments assigns drive capture-seq downstream)."""
    matches = list(_HEADING.finditer(text))
    if not matches:
        return _paragraph_windows(text)
    segments = []
    pre = text[:matches[0].start()].strip()
    if pre:
        segments.append({"loc": {"heading": ""}, "text": pre})
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[m.start():end].strip()
        if body:
            segments.append({"loc": {"heading": m.group(1).strip()}, "text": body})
    return segments


def _paragraph_windows(text: str) -> list[dict]:
    paras = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if not paras:
        return [{"loc": {"heading": ""}, "text": text.strip()}]
    segments, cur, cur_len, part = [], [], 0, 0
    for p in paras:
        if cur and cur_len + len(p) > PARA_SEGMENT_CHARS:
            segments.append({"loc": {"heading": f"part {part}"}, "text": "\n\n".join(cur)})
            part, cur, cur_len = part + 1, [], 0
        cur.append(p)
        cur_len += len(p)
    if cur:
        segments.append({"loc": {"heading": f"part {part}"}, "text": "\n\n".join(cur)})
    return segments
