"""Native text-native extraction → SourceIR (INGESTION_V2_FLOWS §3b).  [NEW / v2]

The first **native** IR extractor (text / md / html), replacing `ir.ir_from_v1_bundle`
for text-native media with *real* char offsets instead of reconstructed ones. Locator =
`{start_char, end_char}` into the **frozen normalized string** (`ir.normalized_text`),
which extraction stores verbatim — never re-normalize it or the offsets rot (tech note,
§ Technology Notes / INGESTION_V2 "Char offsets drift").

Invariant this guarantees: `normalized_text[block.start_char:block.end_char] == block.text`
for **every** block (headings keep their raw `## ` prefix; `level` is parsed alongside).
That exactness is what lets the consume-layer splice-view scroll-and-highlight precisely.

Pure + dependency-free for `from_markdown`/`from_text` (unit-testable, no fetch). `from_html`
adds the trafilatura fetch/boilerplate-strip, reusing the same core.

*To change structure parsing:* this file. *To change flags:* `flagging.py`.
"""
from __future__ import annotations

import logging
import re

from ingest.flagging import flag_source
from ingest.ir import Block, BlockType, CharSpan, Medium, SourceIR

log = logging.getLogger("embedder.ingest.extract_ir")

MIN_CONTENT_CHARS = 1
_ATX_HEADING = re.compile(r"^(#{1,6})\s+\S")


def from_text(text: str, title: str) -> SourceIR:
    """Plain text / markdown paste (the user's own prose). Medium = TEXT."""
    return from_markdown(text, title or "Captured text", Medium.TEXT)


def from_html(ref: str) -> SourceIR:
    """Web article → trafilatura main-content markdown → IR. Medium = HTML.
    Fails loudly on JS-rendered SPAs (< MIN chars), same stance as v1 extract_web."""
    import trafilatura

    downloaded = trafilatura.fetch_url(ref)
    if not downloaded:
        raise RuntimeError(f"fetch failed: {ref}")
    md = trafilatura.extract(downloaded, output_format="markdown",
                             include_images=False, include_links=False)
    if not md or len(md) < 200:
        raise RuntimeError(
            f"main content too thin ({len(md or '')} chars) — likely a JS-rendered SPA")
    meta = trafilatura.extract_metadata(downloaded)
    title = meta.title if (meta and meta.title) else ref
    return from_markdown(md, title, Medium.HTML)


def from_markdown(md: str, title: str, medium: Medium) -> SourceIR:
    """Parse markdown structure into positioned blocks with exact char offsets, then run
    the IR-computable extraction flags. The normalized string is frozen as `md`."""
    body = md if md is not None else ""
    if len(body.strip()) < MIN_CONTENT_CHARS:
        raise RuntimeError("empty text — nothing to ingest")

    blocks = _blocks_from_markdown(body)
    ir = SourceIR(medium=medium, title=title, blocks=blocks, normalized_text=body)
    flag_source(ir)
    return ir


def _blocks_from_markdown(md: str) -> list[Block]:
    """Line scanner tracking char offsets. Heading lines (ATX) are one block each; runs
    of consecutive non-blank, non-heading lines are one paragraph block. Block text is the
    exact source slice `md[start:end]`, so the CharSpan is trivially correct."""
    blocks: list[Block] = []
    order = 0
    para_start: int | None = None
    para_end: int | None = None
    offset = 0

    def flush_para() -> None:
        nonlocal order, para_start, para_end
        if para_start is not None and para_end is not None:
            blocks.append(Block(order_index=order, type=BlockType.PARAGRAPH,
                                text=md[para_start:para_end],
                                locator=CharSpan(para_start, para_end)))
            order += 1
        para_start = para_end = None

    # Keep line terminators so offsets stay exact across the whole string.
    for line in md.splitlines(keepends=True):
        stripped = line.strip()
        content_len = len(line.rstrip("\r\n"))     # chars up to (not incl.) the newline
        line_end = offset + content_len

        m = _ATX_HEADING.match(line)
        if not stripped:
            flush_para()
        elif m:
            flush_para()
            level = len(m.group(1))
            blocks.append(Block(order_index=order, type=BlockType.HEADING, level=level,
                                text=md[offset:line_end], locator=CharSpan(offset, line_end)))
            order += 1
        else:
            if para_start is None:
                para_start = offset
            para_end = line_end

        offset += len(line)

    flush_para()
    return blocks
