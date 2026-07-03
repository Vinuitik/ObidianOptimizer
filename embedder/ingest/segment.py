"""Segmentation — SourceIR → Units (INGESTION_V2_FLOWS §4).  [NEW / v2]

**Invariant: the LLM never chooses boundaries.** Boundaries come from structure +
embedding topic-shift only → reproducible, no hallucinated splits. This is the
deliberate departure from v1's `synthesize.outline()` (which the LLM drove).

Two stages:
  A. structural grouping (deterministic) — TOC leaf, else headings, else token window
  B. semantic refinement (bounded)       — SPLIT oversized at topic-shift; MERGE tiny

A Unit is a contiguous block range; its span is `first_block.start → last_block.end`.
Downstream gets chronology + SEQUENTIAL links for free from `order_index`.

*To change strategy:* this file. *To change sizes:* the env vars below.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Callable, Optional

from ingest.ir import Block, BlockType, CharSpan, Flag, PageBox, SourceIR, TimeSpan

log = logging.getLogger("embedder.ingest.segment")

# ── Size band — DECIDED: 600 words is the hard ceiling (§4) ───────────────────
UNIT_WORDS_MIN   = int(os.environ.get("INGEST_UNIT_WORDS_MIN", "150"))
UNIT_WORDS_MAX   = int(os.environ.get("INGEST_UNIT_WORDS_MAX", "600"))
UNIT_WORDS_CEIL  = int(os.environ.get("INGEST_UNIT_WORDS_CEIL", "600"))  # force split
UNIT_WORDS_FLOOR = int(os.environ.get("INGEST_UNIT_WORDS_FLOOR", "60"))  # force merge
# topic-shift: cosine drop this far below the local mean marks a cut (§4 Stage B)
TOPICSHIFT_DELTA = float(os.environ.get("INGEST_TOPICSHIFT_DELTA", "0.25"))
# structural boundary: split at headings of this level or shallower
SEGMENT_HEADING_LEVEL = int(os.environ.get("INGEST_SEGMENT_HEADING_LEVEL", "2"))

# embed_fn: list[str] -> list[list[float]] (L2-normalized). When None, Stage B split
# uses a deterministic gap fallback so segmentation still runs without a GPU.
EmbedFn = Callable[[list[str]], list[list[float]]]


# ── Unit ──────────────────────────────────────────────────────────────────────

@dataclass
class Unit:
    source_id: str
    block_ids: list[str]          # provenance into the IR
    locator_span: dict            # medium-typed endpoints (see _span_of)
    order_index: int              # → chronology + SEQUENTIAL edges
    raw_text: str                 # concat of block text → drafting input
    flags: list[Flag] = field(default_factory=list)

    @property
    def word_count(self) -> int:
        return _words(self.raw_text)


def _words(text: str) -> int:
    return len(text.split())


# ── Public entry ──────────────────────────────────────────────────────────────

def segment(ir: SourceIR, embed_fn: Optional[EmbedFn] = None) -> list[Unit]:
    """IR → Units. Deterministic given the same IR + embed_fn."""
    blocks = ir.sorted_blocks()
    if not blocks:
        return []

    groups = _stage_a(ir, blocks)                     # list[list[Block]]
    groups = _stage_b(groups, embed_fn)               # split/merge to the size band

    units: list[Unit] = []
    for i, grp in enumerate(groups):
        if not grp:
            continue
        units.append(Unit(
            source_id=ir.source_id,
            block_ids=[b.id for b in grp],
            locator_span=_span_of(grp),
            order_index=i,
            raw_text=_join(grp),
            flags=_inherit_flags(grp),
        ))
    return units


# ── Stage A — structural grouping (deterministic) ─────────────────────────────

def _stage_a(ir: SourceIR, blocks: list[Block]) -> list[list[Block]]:
    if ir.toc:
        return _group_by_toc(ir, blocks)
    if any(b.is_heading for b in blocks):
        return _group_by_headings(blocks)
    return _token_windows(blocks)


def _group_by_toc(ir: SourceIR, blocks: list[Block]) -> list[list[Block]]:
    """Group blocks under TOC leaf sections by page boundaries. Only meaningful for
    paged media (PageBox); other media fall through to heading grouping."""
    if not blocks or not isinstance(blocks[0].locator, PageBox):
        return _group_by_headings(blocks)
    # TOC leaf start pages, ascending. A block belongs to the last leaf whose start
    # page it is at or past.
    starts = sorted((t.page_no or 0) for t in ir.toc if t.page_no is not None)
    if not starts:
        return _group_by_headings(blocks)
    buckets: dict[int, list[Block]] = {}
    for b in blocks:
        page = b.locator.page_no  # type: ignore[union-attr]
        leaf = max((s for s in starts if s <= page), default=starts[0])
        buckets.setdefault(leaf, []).append(b)
    return [buckets[k] for k in sorted(buckets)]


def _group_by_headings(blocks: list[Block]) -> list[list[Block]]:
    """Boundary at each heading with level ≤ SEGMENT_HEADING_LEVEL. Content before
    the first heading is its own group (preamble)."""
    groups: list[list[Block]] = []
    cur: list[Block] = []
    for b in blocks:
        is_boundary = b.is_heading and (b.level or 99) <= SEGMENT_HEADING_LEVEL
        if is_boundary and cur:
            groups.append(cur)
            cur = []
        cur.append(b)
    if cur:
        groups.append(cur)
    return groups


def _token_windows(blocks: list[Block]) -> list[list[Block]]:
    """No structure at all → sliding window over blocks by word budget (target MAX)."""
    groups: list[list[Block]] = []
    cur: list[Block] = []
    cur_words = 0
    for b in blocks:
        w = _words(b.text)
        if cur and cur_words + w > UNIT_WORDS_MAX:
            groups.append(cur)
            cur, cur_words = [], 0
        cur.append(b)
        cur_words += w
    if cur:
        groups.append(cur)
    return groups


# ── Stage B — semantic refinement (bounded) ───────────────────────────────────

def _stage_b(groups: list[list[Block]], embed_fn: Optional[EmbedFn]) -> list[list[Block]]:
    # SPLIT anything over the ceiling at the best topic-shift boundary.
    split: list[list[Block]] = []
    for grp in groups:
        if _words(_join(grp)) > UNIT_WORDS_CEIL and len(grp) > 1:
            split.extend(_split_at_topic_shift(grp, embed_fn))
        else:
            split.append(grp)
    # MERGE undersized adjacent groups forward until they clear the floor.
    return _merge_undersized(split)


def _split_at_topic_shift(grp: list[Block], embed_fn: Optional[EmbedFn]) -> list[list[Block]]:
    """Cut a too-large group where adjacent-block similarity dips furthest (TextTiling).
    Recurses so each piece lands under the ceiling. Deterministic fallback (largest
    text gap by word balance) when no embedder is available."""
    cut = _best_cut_index(grp, embed_fn)
    if cut <= 0 or cut >= len(grp):
        # can't find a seam — hard split at the midpoint so we never exceed the ceiling
        cut = len(grp) // 2 or 1
    left, right = grp[:cut], grp[cut:]
    out: list[list[Block]] = []
    for part in (left, right):
        if _words(_join(part)) > UNIT_WORDS_CEIL and len(part) > 1:
            out.extend(_split_at_topic_shift(part, embed_fn))
        else:
            out.append(part)
    return out


def _best_cut_index(grp: list[Block], embed_fn: Optional[EmbedFn]) -> int:
    """Index i (1..len-1) to cut before. With an embedder: the adjacency whose cosine
    is furthest below the local mean by TOPICSHIFT_DELTA. Without: the seam closest to
    the word-count midpoint (balanced deterministic split)."""
    texts = [b.text for b in grp]
    if embed_fn is not None:
        try:
            vecs = embed_fn(texts)
            sims = [_cosine(vecs[i], vecs[i + 1]) for i in range(len(vecs) - 1)]
            if sims:
                mean = sum(sims) / len(sims)
                # most negative deviation = sharpest topic shift
                best_i, best_drop = 0, 0.0
                for i, s in enumerate(sims):
                    drop = mean - s
                    if drop > best_drop:
                        best_i, best_drop = i, drop
                if best_drop >= TOPICSHIFT_DELTA:
                    return best_i + 1
        except Exception as e:  # embedder hiccup must never sink ingestion
            log.warning("topic-shift embed failed, using gap fallback: %s", e)
    # fallback: balance words either side
    total = _words(_join(grp))
    acc, best_i, best_diff = 0, 1, total
    for i in range(len(grp) - 1):
        acc += _words(grp[i].text)
        diff = abs((total - acc) - acc)
        if diff < best_diff:
            best_diff, best_i = diff, i + 1
    return best_i


def _merge_undersized(groups: list[list[Block]]) -> list[list[Block]]:
    if not groups:
        return groups
    out: list[list[Block]] = [groups[0]]
    for grp in groups[1:]:
        prev = out[-1]
        # merge a tiny group into its predecessor, but not past the ceiling
        if _words(_join(prev)) < UNIT_WORDS_FLOOR and \
           _words(_join(prev)) + _words(_join(grp)) <= UNIT_WORDS_CEIL:
            out[-1] = prev + grp
        else:
            out.append(grp)
    # a trailing tiny group merges backward too
    if len(out) > 1 and _words(_join(out[-1])) < UNIT_WORDS_FLOOR:
        tail = out.pop()
        out[-1] = out[-1] + tail
    return out


# ── Locator span + helpers ────────────────────────────────────────────────────

def _span_of(grp: list[Block]) -> dict:
    """first_block.start → last_block.end, medium-typed (§4 Unit.locator_span)."""
    first, last = grp[0].locator, grp[-1].locator
    if isinstance(first, CharSpan) and isinstance(last, CharSpan):
        return {"kind": "char", "start_char": first.start_char, "end_char": last.end_char}
    if isinstance(first, PageBox) and isinstance(last, PageBox):
        return {
            "kind": "page",
            "page_start": first.page_no, "page_end": last.page_no,
            "bbox_start": first.bbox, "bbox_end": last.bbox,
            "pages": sorted({b.locator.page_no for b in grp
                             if isinstance(b.locator, PageBox)}),
        }
    if isinstance(first, TimeSpan) and isinstance(last, TimeSpan):
        return {"kind": "time", "start_ms": first.start_ms, "end_ms": last.end_ms}
    # mixed/unknown — degrade to a coarse marker rather than crash
    return {"kind": "unknown"}


def _inherit_flags(grp: list[Block]) -> list[Flag]:
    """A Unit inherits its blocks' flags (deduped by code, hardest wins)."""
    by_code: dict[str, Flag] = {}
    for b in grp:
        for f in b.flags:
            cur = by_code.get(f.code)
            if cur is None or (f.severity.value == "hard" and cur.severity.value != "hard"):
                by_code[f.code] = f
    return list(by_code.values())


def _join(grp: list[Block]) -> str:
    return " ".join(b.text for b in grp if b.text).strip()


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0
