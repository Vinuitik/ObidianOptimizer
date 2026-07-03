"""SourceIR — the extraction contract (INGESTION_V2_FLOWS §3).  [NEW / v2]

Extraction's *only* job is `raw bytes → ordered positioned blocks`. It assigns no
concepts and makes no LLM calls. This module is the schema that sits between the
extractors (§3a/§3b/§8a) and segmentation (`segment.py`, §4). Pure dataclasses +
JSON round-trip; **no heavy deps** so it can be imported anywhere (and unit-tested
without a GPU).

Medium taxonomy (the one axis that matters):
  text-native  : txt md html pdf epub — text exists; locator is a position
  inferred-text: audio video          — text from Whisper; locator is time

Locator is the only thing extraction varies on:
  pdf / epub  → PageBox   {page_no, bbox:[x0,y0,x1,y1]}
  txt/md/html → CharSpan  {start_char, end_char}          (offsets into normalized text)
  audio/video → TimeSpan  {start_ms, end_ms}

The unit of anchoring is always the **block**, never page/heading. A block carries a
locator; a Unit (segment.py) is a contiguous block range whose span is
`first_block.start → last_block.end`. Page number is used only for retention (which
screenshot to keep), never for anchoring.

*To change the IR shape:* this file. Downstream (`segment.py`, `retention.py`,
`publish.py`) reads these fields — grep for `SourceIR(` / `Block(` before renaming.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any, Optional


# ── Medium ────────────────────────────────────────────────────────────────────

class Medium(str, Enum):
    PDF = "pdf"
    EPUB = "epub"
    TEXT = "text"      # txt / md
    HTML = "html"
    AUDIO = "audio"
    VIDEO = "video"

    @property
    def text_native(self) -> bool:
        return self in (Medium.PDF, Medium.EPUB, Medium.TEXT, Medium.HTML)

    @property
    def inferred_text(self) -> bool:
        return self in (Medium.AUDIO, Medium.VIDEO)


# ── Block types ───────────────────────────────────────────────────────────────

class BlockType(str, Enum):
    HEADING = "heading"
    PARAGRAPH = "paragraph"
    LIST = "list"
    FIGURE = "figure"
    CAPTION = "caption"
    TABLE = "table"
    SPEECH = "speech"        # inferred-text (transcript cue / utterance)
    KEYFRAME = "keyframe"    # video still (media_ref set, text empty)


# ── Locators (medium-typed, JSON round-trippable via `kind`) ──────────────────

@dataclass
class CharSpan:
    """txt / md / html — offsets into the *normalized* source string. The string
    MUST be frozen at extraction (never re-normalized) or these offsets rot."""
    start_char: int
    end_char: int
    kind: str = "char"


@dataclass
class PageBox:
    """pdf / epub — a bbox on a page. `page_no` also drives retention (which page
    screenshot survives). bbox is [x0, y0, x1, y1] in PDF points."""
    page_no: int
    bbox: list[float]
    kind: str = "page"


@dataclass
class TimeSpan:
    """audio / video — millisecond range. Word-level when available (§8a)."""
    start_ms: int
    end_ms: int
    kind: str = "time"


Locator = CharSpan | PageBox | TimeSpan

_LOCATOR_BY_KIND = {"char": CharSpan, "page": PageBox, "time": TimeSpan}


def locator_from_dict(d: dict) -> Locator:
    cls = _LOCATOR_BY_KIND.get(d.get("kind"))
    if cls is None:
        raise ValueError(f"unknown locator kind: {d.get('kind')!r}")
    return cls(**d)


# ── Extraction flags (§3c / §8d / §9g) — confidence signals that gate commit ──

class Severity(str, Enum):
    HARD = "hard"   # likely WRONG (bad anchor/text) → Unit REVIEWING, not permit-eligible
    SOFT = "soft"   # merely COARSE, not wrong → auto-flows with a badge


@dataclass
class Flag:
    code: str          # READING_ORDER | TABLE | OCR | LAYOUT | NO_STRUCTURE | ASR_LOW | ...
    severity: Severity
    detail: str = ""


# ── Block, Anchor, SourceIR ───────────────────────────────────────────────────

@dataclass
class Block:
    order_index: int                 # the spine: chronology + SEQUENTIAL links for free
    locator: Locator
    type: BlockType
    text: str = ""                   # "" for figure / keyframe
    level: Optional[int] = None      # heading depth if type == HEADING
    media_ref: Optional[str] = None  # figure crop / keyframe path
    flags: list[Flag] = field(default_factory=list)
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])

    @property
    def is_heading(self) -> bool:
        return self.type == BlockType.HEADING

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "order_index": self.order_index,
            "locator": asdict(self.locator),
            "type": self.type.value,
            "text": self.text,
            "level": self.level,
            "media_ref": self.media_ref,
            "flags": [asdict(f) for f in self.flags],
        }

    @staticmethod
    def from_dict(d: dict) -> "Block":
        return Block(
            id=d.get("id", uuid.uuid4().hex[:12]),
            order_index=d["order_index"],
            locator=locator_from_dict(d["locator"]),
            type=BlockType(d["type"]),
            text=d.get("text", ""),
            level=d.get("level"),
            media_ref=d.get("media_ref"),
            flags=[Flag(code=f["code"], severity=Severity(f["severity"]),
                        detail=f.get("detail", "")) for f in d.get("flags", [])],
        )


@dataclass
class Anchor:
    """A retained representation keyed by position. pdf: a page screenshot; av: a
    timeline marker. Retained lazily (§6) — only kept if a committed Unit references it."""
    key: str                # page_no as str (pdf) | marker id (av)
    image_path: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None


@dataclass
class TocEntry:
    title: str
    level: int
    page_no: Optional[int] = None
    char: Optional[int] = None


@dataclass
class SourceIR:
    medium: Medium
    title: str
    blocks: list[Block] = field(default_factory=list)
    anchors: list[Anchor] = field(default_factory=list)
    toc: list[TocEntry] = field(default_factory=list)
    # For text-native media: the frozen normalized string CharSpan offsets index into.
    # Stored with the source until commit; None for inferred-text media.
    normalized_text: Optional[str] = None
    source_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])

    # ── invariants / helpers ──
    def sorted_blocks(self) -> list[Block]:
        return sorted(self.blocks, key=lambda b: b.order_index)

    def has_structure(self) -> bool:
        """True if there's a usable structural prior for Stage A (§4)."""
        return bool(self.toc) or any(b.is_heading for b in self.blocks)

    def hard_flags(self) -> list[Flag]:
        return [f for b in self.blocks for f in b.flags if f.severity == Severity.HARD]

    def to_dict(self) -> dict:
        return {
            "source_id": self.source_id,
            "medium": self.medium.value,
            "title": self.title,
            "blocks": [b.to_dict() for b in self.blocks],
            "anchors": [asdict(a) for a in self.anchors],
            "toc": [asdict(t) for t in self.toc],
            "normalized_text": self.normalized_text,
        }

    @staticmethod
    def from_dict(d: dict) -> "SourceIR":
        return SourceIR(
            source_id=d.get("source_id", uuid.uuid4().hex[:12]),
            medium=Medium(d["medium"]),
            title=d["title"],
            blocks=[Block.from_dict(b) for b in d.get("blocks", [])],
            anchors=[Anchor(**a) for a in d.get("anchors", [])],
            toc=[TocEntry(**t) for t in d.get("toc", [])],
            normalized_text=d.get("normalized_text"),
        )


def new_block_id() -> str:
    return uuid.uuid4().hex[:12]


# ── v1 → SourceIR bridge (transitional) ───────────────────────────────────────

_V1_MEDIUM = {
    "text": Medium.TEXT, "web": Medium.HTML, "web_dom": Medium.HTML, "html": Medium.HTML,
    "pdf": Medium.PDF, "epub": Medium.EPUB,
    "audio": Medium.AUDIO, "video": Medium.VIDEO, "youtube": Medium.VIDEO,
}


def ir_from_v1_bundle(bundle: dict) -> "SourceIR":
    """Adapt a live v1 extraction bundle (`{source, segments:[{loc,text}], media}`)
    into a SourceIR so `segment.py` can run **before** the extractors are rewritten to
    emit IR natively. Transitional — it reconstructs locators from what v1 recorded:

      text/html : builds a frozen normalized string from the segments and assigns each
                  block a real CharSpan into it (heading segments also get a HEADING
                  block so Stage A heading-grouping works).
      pdf/epub  : PageBox per segment; bbox is unknown in v1 → [] (a SOFT signal only).
      audio/vid : TimeSpan from loc.t_start/t_end seconds → ms.

    Once §3a/§3b/§8a extractors emit SourceIR directly, delete this and its callers.
    """
    src = bundle.get("source", {})
    medium = _V1_MEDIUM.get(src.get("type", "text"), Medium.TEXT)
    title = src.get("title") or "Untitled"
    segments = bundle.get("segments", [])

    blocks: list[Block] = []
    order = 0
    norm_parts: list[str] = []
    cursor = 0

    for seg in segments:
        loc = seg.get("loc", {})
        text = (seg.get("text") or "").strip()
        if not text:
            continue

        if medium.text_native and "page" not in loc:
            heading = loc.get("heading", "")
            if heading:
                start = cursor
                blocks.append(Block(order_index=order, type=BlockType.HEADING,
                                    level=2, text=heading,
                                    locator=CharSpan(start, start + len(heading))))
                order += 1
                norm_parts.append(heading)
                cursor += len(heading) + 2  # + "\n\n"
            start = cursor
            blocks.append(Block(order_index=order, type=BlockType.PARAGRAPH, text=text,
                                locator=CharSpan(start, start + len(text))))
            order += 1
            norm_parts.append(text)
            cursor += len(text) + 2

        elif "page" in loc:
            blocks.append(Block(order_index=order, type=BlockType.PARAGRAPH, text=text,
                                locator=PageBox(page_no=int(loc["page"]), bbox=[])))
            order += 1

        elif "t_start" in loc:
            blocks.append(Block(order_index=order, type=BlockType.SPEECH, text=text,
                                locator=TimeSpan(start_ms=int(float(loc["t_start"]) * 1000),
                                                 end_ms=int(float(loc.get("t_end", loc["t_start"])) * 1000))))
            order += 1
        else:
            # unknown loc → coarse char block at end of the running string
            start = cursor
            blocks.append(Block(order_index=order, type=BlockType.PARAGRAPH, text=text,
                                locator=CharSpan(start, start + len(text))))
            order += 1
            norm_parts.append(text)
            cursor += len(text) + 2

    toc = [TocEntry(title=c["title"], level=1)
           for c in src.get("chapters", []) if c.get("title")]
    normalized = "\n\n".join(norm_parts) if norm_parts else None
    return SourceIR(medium=medium, title=title, blocks=blocks, toc=toc,
                    normalized_text=normalized)
