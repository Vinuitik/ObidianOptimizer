"""v2 orchestrator — chains the deterministic backbone (INGESTION_V2_FLOWS §2).  [NEW / v2]

    Extract → SourceIR → Segment → Units → Draft → Place → Commit → Retain
    (extract_ir)  (ir)    (segment)         (inject) (locator)      (retention)

This is the **feature-flagged** seam that ties the pure modules together into one
`run(ir) → PipelineResult`. It is **NOT wired into the live trigger**: `jobs.py` still
runs the v1 path. The cutover (task 3 of the "wiring that remains" list) is a caller in
`jobs.py` guarded by `v2_enabled()` that swaps `synthesize.outline()`'s boundary role for
`segment.segment(...)` and drafts each Unit. Until then this module is imported by tests
only, exactly like the stages it chains.

Design invariants preserved end to end:
  - **The LLM never chooses boundaries** — `segment.segment()` does (structure + topic-shift).
    The only LLM here is the injected `draft_fn`, which *writes* a Unit, never splits it.
  - **Deterministic given (ir, embed_fn, draft_fn)** — no hidden global state, no I/O. The
    real wiring injects `embed_fn=model_runtime.embed` and a `synthesize`-backed `draft_fn`;
    tests inject pure stubs and run GPU/LLM-free.
  - **HARD flags gate auto-commit** (§3c). A Unit with a HARD flag lands `REVIEWING` (not
    permit-eligible); clean Units are `DRAFT` and auto-flow. Retention is computed over the
    auto-committable Units only — delete-by-default means an unreferenced-until-reviewed
    page is not kept prematurely.

*To change the chain / staging:* this file. *To change a stage:* its own module
(`extract_ir.py`, `segment.py`, `retention.py`, `locator.py`).
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Callable, Optional

from ingest import extract_ir, locator, retention
from ingest.ir import BlockType, Medium, Severity, SourceIR
from ingest.retention import KeptFragment, RetentionPlan
from ingest.segment import EmbedFn, Unit, segment

log = logging.getLogger("embedder.ingest.pipeline_v2")

# ── Feature flag (default OFF) — the live cutover checks this, never the tests ──
INGEST_V2 = os.environ.get("INGEST_V2", "off")


def v2_enabled() -> bool:
    """True when the v2 pipeline should run in the live path. Read dynamically so a
    caller (jobs.py) can be gated without importing a frozen module constant."""
    return os.environ.get("INGEST_V2", "off").lower() in ("on", "1", "true", "yes")


def guard() -> None:
    """The live caller calls this before `run()`; raises if v2 is disabled. Pure callers
    (tests, offline analysis) skip it — the chain itself is not flag-gated."""
    if not v2_enabled():
        raise RuntimeError("INGEST_V2 is off — refusing to run v2 pipeline in the live path")


# ── Note lifecycle (§7 state machine: DRAFT → REVIEWING → COMMITTED) ───────────

class Lifecycle(str):
    DRAFT = "DRAFT"           # clean, permit-eligible, auto-flows
    REVIEWING = "REVIEWING"   # has a HARD flag → needs the user before commit
    COMMITTED = "COMMITTED"   # permitted (this module never reaches it; publish does)


# draft_fn: (Unit, title) -> markdown body. The real wiring wraps synthesize._write_body();
# None → identity echo so the chain runs without an LLM (testable).
DraftFn = Callable[[Unit, str], str]


@dataclass
class DraftedNote:
    """One Unit turned into a draft note, carrying its provenance and review state."""
    unit: Unit
    title: str
    body: str
    splice: dict                       # locator.SpliceView.as_dict() — consume-layer region
    lifecycle: str
    flags: list = field(default_factory=list)

    @property
    def needs_review(self) -> bool:
        return self.lifecycle == Lifecycle.REVIEWING

    def as_dict(self) -> dict:
        return {
            "title": self.title,
            "body": self.body,
            "splice": self.splice,
            "lifecycle": self.lifecycle,
            "order_index": self.unit.order_index,
            "block_ids": self.unit.block_ids,
            "flags": [{"code": f.code, "severity": f.severity.value} for f in self.flags],
        }


@dataclass
class PipelineResult:
    ir: SourceIR
    units: list[Unit]
    notes: list[DraftedNote]
    retention: RetentionPlan

    @property
    def needs_review(self) -> bool:
        """Whole-source gate: any Unit diverted to REVIEWING blocks hands-off commit."""
        return any(n.needs_review for n in self.notes)

    def as_dict(self) -> dict:
        return {
            "source_id": self.ir.source_id,
            "medium": self.ir.medium.value,
            "title": self.ir.title,
            "needs_review": self.needs_review,
            "notes": [n.as_dict() for n in self.notes],
            "retention": self.retention.as_dict(),
        }


# ── Core chain ─────────────────────────────────────────────────────────────────

def run(
    ir: SourceIR,
    draft_fn: Optional[DraftFn] = None,
    embed_fn: Optional[EmbedFn] = None,
    kept_fragments: Optional[list[KeptFragment]] = None,
    source_blob_path: Optional[str] = None,
) -> PipelineResult:
    """Chain Segment → Draft → Place → Retain over an already-extracted `SourceIR`.

    `embed_fn` drives Stage B topic-shift (None → deterministic split, GPU-free).
    `draft_fn` writes each Unit (None → echo the raw text, LLM-free). The retention plan is
    computed over the **auto-committable** Units (no HARD flag) — the ones that would commit
    hands-off — because delete-by-default must not keep pages only a still-in-review Unit
    references. Pure: no file I/O, no network."""
    draft_fn = draft_fn or _echo_draft

    units = segment(ir, embed_fn=embed_fn)                      # boundaries (deterministic)

    # Titles up front so collisions (2+ Units under one TOC chapter) get "… (part k)" before
    # drafting — the reused chapter names stay unique and the LLM drafts against the final title.
    titles = _dedup_titles([_unit_title(u, ir) for u in units])
    notes: list[DraftedNote] = []
    for unit, title in zip(units, titles):
        body = draft_fn(unit, title)                            # the ONLY LLM (injected)
        splice = locator.resolve_splice(unit, ir).as_dict()    # consume-layer region
        notes.append(DraftedNote(
            unit=unit, title=title, body=body, splice=splice,
            lifecycle=_lifecycle_of(unit), flags=list(unit.flags),
        ))

    committable = [n.unit for n in notes if n.lifecycle == Lifecycle.DRAFT]
    plan = retention.compute_retention(
        ir, committable, kept_fragments=kept_fragments, source_blob_path=source_blob_path)

    return PipelineResult(ir=ir, units=units, notes=notes, retention=plan)


# ── Convenience entry points (build the IR, then run the chain) ────────────────

def run_text(text: str, title: str, **kw) -> PipelineResult:
    """Raw text / markdown paste → native IR (real char offsets) → chain."""
    return run(extract_ir.from_text(text, title), **kw)


def run_html(ref: str, **kw) -> PipelineResult:
    """Web article URL → trafilatura IR → chain. (Network in `from_html`, not here.)"""
    return run(extract_ir.from_html(ref), **kw)


def run_v1_bundle(bundle: dict, **kw) -> PipelineResult:
    """Transitional: a live v1 extraction bundle (pdf/av still emit v1 shapes) → bridged
    IR → chain. Lets pdf/av flow through v2 before their native extractors land."""
    from ingest.ir import ir_from_v1_bundle
    return run(ir_from_v1_bundle(bundle), **kw)


# ── Helpers ────────────────────────────────────────────────────────────────────

def _lifecycle_of(unit: Unit) -> str:
    """HARD flag → REVIEWING (not permit-eligible, §3c); else DRAFT (auto-flows)."""
    if any(f.severity == Severity.HARD for f in unit.flags):
        return Lifecycle.REVIEWING
    return Lifecycle.DRAFT


def _unit_title(unit: Unit, ir: SourceIR) -> str:
    """Title for a Unit, in priority order:
      1. a real HEADING block inside the Unit — it IS the section name;
      2. the enclosing TOC chapter name (paged media) — reuse the outline the boundaries were
         already anchored to, so a mid-chapter Unit reads "Agent Ops", not "Introduction (7)";
      3. `<source title> (n)` as a last resort (no heading, no TOC — e.g. plain text)."""
    owned = set(unit.block_ids)
    blocks = [b for b in ir.sorted_blocks() if b.id in owned]
    for b in blocks:
        if b.type == BlockType.HEADING and b.text.strip():
            return b.text.lstrip("#").strip()
    # Enclosing TOC leaf: the last outline entry whose start page is at/before the Unit's first.
    page = min((getattr(b.locator, "page_no", None) for b in blocks
                if getattr(b.locator, "page_no", None) is not None), default=None)
    if page is not None and ir.toc:
        enclosing = [t for t in ir.toc if t.page_no is not None and t.page_no <= page and t.title.strip()]
        if enclosing:
            return max(enclosing, key=lambda t: t.page_no).title.strip()
    return f"{ir.title} ({unit.order_index + 1})"


def _dedup_titles(titles: list[str]) -> list[str]:
    """Reused chapter names collide when one chapter is size-split into several Units. Append
    "(part k)" in order so titles stay unique; singletons are left untouched."""
    from collections import Counter
    counts = Counter(titles)
    seen: dict[str, int] = {}
    out = []
    for t in titles:
        if counts[t] > 1:
            seen[t] = seen.get(t, 0) + 1
            out.append(f"{t} (part {seen[t]})")
        else:
            out.append(t)
    return out


def _echo_draft(unit: Unit, title: str) -> str:
    """Default LLM-free drafter: return the Unit's raw text unchanged. Keeps the chain
    runnable in tests; the live wiring injects a `synthesize._write_body()`-backed fn."""
    return unit.raw_text
