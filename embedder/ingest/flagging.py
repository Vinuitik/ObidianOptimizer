"""Extraction flagging — the IR-computable subset (INGESTION_V2_FLOWS §3c/§8d).  [NEW / v2]

A flag is a confidence signal on a block (inherited by its Unit) that gates auto-commit:
HARD → the Unit goes REVIEWING (not permit-eligible); SOFT → auto-flows with a badge.

Most §3c checks need `fitz` layout signals (column histograms, table detection) and live
in `extract_pdf.py` where that data exists. This module holds the checks computable from
a **SourceIR alone** — no PDF library, no ML — so they apply to any medium and are unit-
testable. Extractors call `flag_source(ir)` after building the IR; richer layout checks
add their own flags upstream.

ON by default (`INGEST_FLAG_MODE`). Flag-by-default is a starting stance — if noisy,
demote HARD→SOFT rather than turning off.
"""
from __future__ import annotations

import os

from ingest.ir import BlockType, CharSpan, Flag, Medium, Severity, SourceIR

FLAG_MODE = os.environ.get("INGEST_FLAG_MODE", "on")

# One block holding more than this fraction of the whole source's words is suspiciously
# coarse (extractor failed to break it up) → OVERSIZE_BLOCK (SOFT).
OVERSIZE_BLOCK_RATIO = float(os.environ.get("INGEST_OVERSIZE_BLOCK_RATIO", "0.6"))
# An inferred-text (A/V) block longer than this with no internal break is weakly
# segmented → LONG_UNBROKEN (SOFT, §8d).
LONG_UNBROKEN_WORDS = int(os.environ.get("INGEST_LONG_UNBROKEN_WORDS", "500"))


def _words(text: str) -> int:
    return len(text.split())


def flag_source(ir: SourceIR) -> SourceIR:
    """Annotate `ir.blocks[*].flags` in place (and return `ir`) with the checks that need
    only the IR. Idempotent per code: re-running won't duplicate a flag of the same code."""
    if FLAG_MODE != "on":
        return ir

    total_words = sum(_words(b.text) for b in ir.blocks) or 1

    # NO_STRUCTURE (SOFT): no TOC and no headings → Stage A falls to token windows, which
    # can cut mid-concept. A whole-source signal, attached to the first block.
    if ir.blocks and not ir.has_structure():
        _add(ir.blocks[0], "NO_STRUCTURE", Severity.SOFT,
             "no toc and no headings — segmentation is token-window only")

    for b in ir.blocks:
        w = _words(b.text)

        # OVERSIZE_BLOCK (SOFT): one block dominates the source → coarse extraction.
        if w and w / total_words > OVERSIZE_BLOCK_RATIO and len(ir.blocks) > 1:
            _add(b, "OVERSIZE_BLOCK", Severity.SOFT,
                 f"{w}/{total_words} words in a single block")

        # LONG_UNBROKEN (SOFT, §8d): a long A/V speech block with no pause boundary.
        if ir.medium in (Medium.AUDIO, Medium.VIDEO) and \
                b.type == BlockType.SPEECH and w > LONG_UNBROKEN_WORDS:
            _add(b, "LONG_UNBROKEN", Severity.SOFT,
                 f"{w}-word speech run with no boundary")

    return ir


def _add(block, code: str, severity: Severity, detail: str = "") -> None:
    if any(f.code == code for f in block.flags):
        return
    block.flags.append(Flag(code=code, severity=severity, detail=detail))
