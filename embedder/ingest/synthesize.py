"""Synthesis — the ONLY place the ingest pipeline talks to an LLM.

Two constrained passes (INGEST_AGENT_ARCH stage 4):
  OUTLINE: numbered segments → JSON note plans (schema-validated, retries ≤ 2)
  WRITE:   one call per planned note → markdown body only

Frontmatter, source backlinks, and media embeds are injected DETERMINISTICALLY
around the LLM body — the model never chooses media placement.

LLM routing: host-wrapper POST /complete — the FULL router chain (free
providers first, claude-cli subscription credits dead last), exactly like the
flashcard agent. No direct API keys here, ever. The prompts are provider-
agnostic by design: output is schema-validated with a retry budget, so
whichever model the router lands on either conforms or gets re-prompted.
SYNTH_MODEL only matters if the chain reaches claude-cli.
"""
import json
import logging
import os
import re
from datetime import date

import httpx

from agent_reports import AgentReport
from ingest import bundle as bundle_util

log = logging.getLogger("embedder.ingest.synthesize")

WRAPPER_URL = os.environ.get("WRAPPER_URL", "http://host.docker.internal:5001")
SYNTH_MODEL = os.environ.get("SYNTH_MODEL", "haiku")  # applies only at claude-cli
MAX_RETRIES = 2
LLM_TIMEOUT_S = int(os.environ.get("INGEST_LLM_TIMEOUT_S", "300"))

OUTLINE_SYSTEM = (
    "You are a knowledge-base librarian. You split source material into the "
    "smallest set of self-contained concept notes. You output ONLY valid JSON."
)

OUTLINE_PROMPT = """Source: {title} ({source_type})
Segments below are numbered [id @ location].

Plan 1..N Obsidian notes covering this material. Group segments by concept —
a note must stand alone. Prefer FEWER, richer notes; split only on real topic
boundaries (chapters {chapters}).

Return ONLY this JSON shape:
{{"notes": [{{"title": "...", "segment_ids": [0,1], "tags": ["..."],
             "summary_hint": "one line on what this note covers"}}]}}

Rules: every segment id appears in exactly one note; titles are short noun
phrases unique within this plan; 1-4 lowercase tags per note.

SEGMENTS:
{segments}"""

# Text the user captured themselves (paste / selection / DOM article). The risk
# here is padding/slop, so the prompt leans hard on faithfulness and concision —
# the source is already prose, not a transcript to be reconstructed.
TEXT_OUTLINE_PROMPT = """The source below is text the user captured (article, notes,
or a selection): {title}. Segments are numbered [id @ location].

Break it into the smallest set of self-contained concept notes. Group segments by
idea; each note must stand alone and be worth keeping. Prefer FEWER, richer notes;
split only on real topic boundaries (chapters {chapters}). Do NOT add anything that
is not in the captured text — no outside knowledge, no filler.

Return ONLY this JSON shape:
{{"notes": [{{"title": "...", "segment_ids": [0,1], "tags": ["..."],
             "summary_hint": "one line on what this note covers"}}]}}

Rules: every segment id appears in exactly one note; titles are short noun
phrases unique within this plan; 1-4 lowercase tags per note.

SEGMENTS:
{segments}"""

# Source types whose segments are already prose (not a transcript) → text prompt.
_TEXT_SOURCE_TYPES = {"text", "note", "web", "web_dom"}

WRITE_SYSTEM = (
    "You write dense, well-structured Obsidian notes in markdown. Headings with "
    "##, code in fenced blocks, math in $...$. No frontmatter, no top-level "
    "title — the caller adds those. Never invent facts absent from the source."
)

WRITE_PROMPT = """Write the body of the note titled "{title}".
Hint: {summary_hint}

Use ONLY the source segments below. Structure freely (##-sections, lists,
tables) but stay faithful to the source. Start directly with content.

SOURCE SEGMENTS:
{segments}"""


class SynthesisError(Exception):
    """Synthesis failed. `status` mirrors the wrapper HTTP code when the failure was
    an LLM call — 503 means all providers were cooling (retryable → job DEFERRED),
    anything else is treated as terminal. None when the error wasn't an LLM call."""
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


# ── LLM plumbing ─────────────────────────────────────────────────────────

def _complete(prompt: str, system: str) -> str:
    # priority=high: ingest synthesis wins scarce LLM tokens over flashcards (medium)
    # and image-captions (low). See host-wrapper llm_router.PRIORITY.
    resp = httpx.post(f"{WRAPPER_URL}/complete",
                      json={"prompt": prompt, "system": system,
                            "model": SYNTH_MODEL, "priority": "high"},
                      timeout=LLM_TIMEOUT_S)
    if resp.status_code != 200:
        raise SynthesisError(f"wrapper /complete {resp.status_code}: {resp.text[:300]}",
                             status=resp.status_code)
    return resp.json()["text"]


def _json_object(text: str):
    t = text.strip()
    if t.startswith("```"):
        t = t.split("```")[1]
        t = t[4:] if t.startswith("json") else t
    try:
        return json.loads(t)
    except json.JSONDecodeError:
        lo, hi = t.find("{"), t.rfind("}")
        if 0 <= lo < hi:
            return json.loads(t[lo:hi + 1])
        raise


# ── pass 1: outline ──────────────────────────────────────────────────────

def outline(bundle: dict) -> list[dict]:
    segs = bundle_util.number_segments(bundle)
    subject = bundle["source"].get("title") or bundle["source"].get("ref", "untitled")
    rep = AgentReport("ingest-outline", subject)
    rep.input("source", {k: bundle["source"].get(k) for k in ("type", "title", "ref")})
    rep.input("segments", bundle_util.render_segments(segs))
    plans = []
    try:
        for window in bundle_util.windows(segs):
            plans.extend(_outline_window(bundle, window, rep))
        rep.output("planned notes", plans)
        rep.save(status="ok" if plans else "empty")
    except SynthesisError as e:
        rep.output("error", str(e))
        rep.save(status="error")
        raise
    return plans


def _outline_window(bundle: dict, window: list[dict], rep: AgentReport) -> list[dict]:
    source_type = bundle["source"]["type"]
    chapters = [c.get("title") for c in bundle["source"].get("chapters", [])] or "none"
    if source_type in _TEXT_SOURCE_TYPES:
        prompt = TEXT_OUTLINE_PROMPT.format(
            title=bundle["source"].get("title", "untitled"),
            chapters=chapters,
            segments=bundle_util.render_segments(window),
        )
    else:
        prompt = OUTLINE_PROMPT.format(
            title=bundle["source"].get("title", "untitled"),
            source_type=source_type,
            chapters=chapters,
            segments=bundle_util.render_segments(window),
        )
    valid_ids = {s["id"] for s in window}
    last_err = None
    for attempt in range(MAX_RETRIES + 1):
        raw = _complete(prompt if attempt == 0
                        else f"{prompt}\n\nYour previous reply was invalid: {last_err}. "
                             f"Return ONLY the corrected JSON.", OUTLINE_SYSTEM)
        rep.said(f"raw outline (ids {min(valid_ids, default='?')}-"
                 f"{max(valid_ids, default='?')}, attempt {attempt})", raw)
        try:
            plan = _json_object(raw)
            notes = plan["notes"]
            assert isinstance(notes, list) and notes, "notes must be a non-empty list"
            for n in notes:
                assert n.get("title"), "every note needs a title"
                n["segment_ids"] = [i for i in n.get("segment_ids", []) if i in valid_ids]
                n.setdefault("tags", [])
                n.setdefault("summary_hint", "")
            notes = [n for n in notes if n["segment_ids"]]
            assert notes, "no note kept any valid segment id"
            return notes
        except (json.JSONDecodeError, AssertionError, KeyError, TypeError) as e:
            last_err = str(e)[:200]
            log.warning("outline attempt %d invalid: %s", attempt + 1, last_err)
    raise SynthesisError(f"outline failed after {MAX_RETRIES + 1} attempts: {last_err}")


# ── pass 2: write ────────────────────────────────────────────────────────

def write_note(bundle: dict, plan: dict, all_segments: list[dict]) -> str:
    by_id = {s["id"]: s for s in all_segments}
    body, segs = _write_body(bundle, plan, by_id)
    return assemble(bundle, plan, segs, body)


def _write_body(bundle: dict, plan: dict, by_id: dict[int, dict]) -> tuple[str, list[dict]]:
    """One WRITE call → markdown body + the segments it was built from."""
    segs = [by_id[i] for i in plan["segment_ids"] if i in by_id]
    body = _complete(WRITE_PROMPT.format(
        title=plan["title"], summary_hint=plan.get("summary_hint", ""),
        segments=bundle_util.render_segments(segs)), WRITE_SYSTEM)
    return _strip_fences(body), segs


def write_unit_body(title: str, raw_text: str, summary_hint: str = "") -> str:
    """v2 WRITE pass: draft ONE segment-v2 Unit (boundaries already fixed by
    `segment.py`). Same WRITE prompt/system as `_write_body`, but the input is the
    Unit's `raw_text` directly — `outline()`'s boundary role is retired (INGESTION_V2 §2)."""
    body = _complete(WRITE_PROMPT.format(
        title=title, summary_hint=summary_hint, segments=raw_text), WRITE_SYSTEM)
    return _strip_fences(body)


TITLE_SYSTEM = (
    "You name a note from a slice of source content — usually a video/audio transcript "
    "chapter. Reply with ONLY the title: a short, specific noun phrase (2–7 words) naming "
    "what THIS slice is about. No quotes, no numbering, no trailing punctuation, no 'Part N'."
)

TITLE_PROMPT = """Name this section of "{source}". Return only the title.

CONTENT:
{segments}"""


def write_unit_title(raw_text: str, source: str = "the source") -> str:
    """Name ONE v2 Unit that has no structural title (chiefly an A/V transcript chapter —
    videos rarely ship yt-dlp chapters). One cheap LLM call; the caller (`pipeline_v2`) uses
    the "<source> (n)" fallback if this raises. Trimmed to a single clean line."""
    raw = _complete(TITLE_PROMPT.format(source=source, segments=raw_text[:4000]), TITLE_SYSTEM)
    line = _strip_fences(raw).strip().splitlines()[0] if raw.strip() else ""
    return line.strip().strip('"').strip("#").strip()[:120]


def _strip_fences(body: str) -> str:
    t = body.strip()
    if t.startswith("```"):
        parts = t.split("```")
        if len(parts) >= 3:
            t = parts[1]
            t = t[len("markdown"):] if t.startswith("markdown") else t
    return t.strip()


# ── in-place assembly (inject below an embed, no frontmatter) ─────────────

def build_inplace_body(bundle: dict, plans: list[dict],
                       all_segments: list[dict]) -> str:
    """Synthesize ALL plans into ONE block injected below a resource embed.

    Unlike assemble() (standalone note: frontmatter + sr fields + #review),
    this returns only the inner body — the parent note owns its frontmatter.
    Each plan becomes a ## section; media interleave by loc; one source footer.
    """
    by_id = {s["id"]: s for s in all_segments}
    sections, used = [], []
    for plan in plans:
        body, segs = _write_body(bundle, plan, by_id)
        used.extend(segs)
        media_lines = _media_for_segments(bundle, segs)
        section = f"## {plan['title']}\n\n{body.rstrip()}"
        if media_lines:
            section += "\n\n" + "\n".join(media_lines)
        sections.append(section)
    return "\n\n".join(sections) + "\n\n" + _source_section(bundle["source"], used)


# ── in-place assembly, v2 (pre-drafted Units) ─────────────────────────────

def _span_to_segs(span: dict) -> list[dict]:
    """A v2 Unit.locator_span → v1-shaped `[{loc}]` stubs so the existing deterministic
    helpers (`_media_for_segments`, `_source_section`) work unchanged for v2. Text spans
    carry no page/time meta → no media, ref-only footer."""
    kind = span.get("kind")
    if kind == "page":
        pages = span.get("pages", [])
        segs = [{"loc": {"page": p}} for p in pages]
        # Sub-page region (bbox_start/end are PDF points). Only meaningful when the Unit sits
        # on ONE page — a start point on page A and an end point on page B don't make a rect —
        # so attach it just then. This is what lights up the review "show region" highlight;
        # for multi-page or v1 (page-only) spans there's no bbox and the whole page shows.
        bs, be = span.get("bbox_start"), span.get("bbox_end")
        if bs and be and len(segs) == 1:
            segs[0]["loc"]["bbox"] = [bs[0], bs[1], be[0], be[1]]
        return segs
    if kind == "time":
        return [{"loc": {"t_start": span.get("start_ms", 0) / 1000,
                         "t_end": span.get("end_ms", 0) / 1000}}]
    return []


def build_inplace_body_v2(source: dict, drafted: list[dict], media: list[dict]) -> str:
    """v2 counterpart of `build_inplace_body`: the bodies are ALREADY drafted per Unit
    (`write_unit_body`), so this only assembles — one `## title` section per Unit, media
    interleaved by locator span, one source footer over all spans. No LLM here."""
    bundle = {"source": source, "media": media}
    sections, all_segs = [], []
    for d in drafted:
        segs = _span_to_segs(d["span"])
        all_segs.extend(segs)
        section = f"## {d['title']}\n\n{d['body'].rstrip()}"
        media_lines = _media_for_segments(bundle, segs)
        if media_lines:
            section += "\n\n" + "\n".join(media_lines)
        sections.append(section)
    return "\n\n".join(sections) + "\n\n" + _source_section(source, all_segs)


# ── deterministic assembly (standalone note) ─────────────────────────────

def assemble(bundle: dict, plan: dict, segs: list[dict], body: str) -> str:
    src = bundle["source"]
    tags = ", ".join(t.strip().replace(" ", "-") for t in plan.get("tags", []))
    fm = (f"---\nsource: {src.get('ref', '')}\ncreated: {date.today().isoformat()}\n"
          f"tags: [{tags}]\nsr-due: {date.today().isoformat()}\n"
          f"sr-interval: 3\nsr-ease: 200\n---\n")

    media_lines = _media_for_segments(bundle, segs)
    media_block = ("\n".join(media_lines) + "\n\n") if media_lines else ""

    return (fm + f"\n# {plan['title']}\n\n" + body.rstrip() + "\n\n"
            + media_block + _source_section(src, segs) + "\n\n#review\n")


def _media_for_segments(bundle: dict, segs: list[dict]) -> list[str]:
    """Media whose loc falls inside any of this note's segment ranges,
    in source order — placement is deterministic, never the LLM's call."""
    lines = []
    for m in bundle.get("media", []):
        loc = m.get("loc", {})
        if _loc_in_segments(loc, segs):
            name = m["path"].rsplit("/", 1)[-1]
            cue = f"  \n*{m['cue_text']}*" if m.get("cue_text") else ""
            lines.append(f"![[{name}]]{cue}")
    return lines


def _loc_in_segments(loc: dict, segs: list[dict]) -> bool:
    if "t" in loc:
        return any(s["loc"].get("t_start", 1e18) <= loc["t"] <= s["loc"].get("t_end", -1)
                   for s in segs)
    if "page" in loc:
        return any(s["loc"].get("page") == loc["page"] for s in segs)
    return False


def _source_section(src: dict, segs: list[dict]) -> str:
    ref = src.get("ref", "")
    lines = ["## Source", ref]
    # Local copy the ingest persisted into the vault (LOCAL_MEDIA_RETENTION stage 2) →
    # the review splice viewer plays THIS file instead of the external ref. Parsed back by
    # frontend inboxParse.parseSourceRegion.
    if src.get("local"):
        lines.append(f"local: {src['local']}")
    ts = [s["loc"]["t_start"] for s in segs if "t_start" in s.get("loc", {})]
    te = [s["loc"]["t_end"] for s in segs if "t_end" in s.get("loc", {})]
    pages = sorted({s["loc"]["page"] for s in segs if "page" in s.get("loc", {})})
    if ts and ref.startswith("http"):
        t0 = int(min(ts))
        sep = "&" if "?" in ref else "?"
        lines[1] = f"[{bundle_util._fmt_ts(t0)}]({ref}{sep}t={t0}s) · {ref}"
    elif ts:
        lines.append(f"from {bundle_util._fmt_ts(min(ts))}")
    if ts:
        # Machine-readable clip bounds in SECONDS so the splice viewer plays JUST this note's
        # span of the video, not from the start to end-of-file. Parsed by parseSourceRegion.
        t0 = int(min(ts))
        t1 = int(max(te)) if te else None
        if t1 and t1 > t0:
            lines.append(f"clip: {t0}-{t1}")
    if pages:
        lines.append("pages: " + ", ".join(str(p) for p in pages))
    # Sub-page highlight region(s), PDF points: `bbox: <page> x0 y0 x1 y1`. Only v2 page spans
    # carry one (see _span_to_segs); the review viewer draws it as a toggle-able rectangle.
    for s in segs:
        b = s.get("loc", {}).get("bbox")
        if b and "page" in s["loc"]:
            lines.append("bbox: %d %.1f %.1f %.1f %.1f" % (s["loc"]["page"], b[0], b[1], b[2], b[3]))
    return "\n".join(lines)


def slugify(title: str) -> str:
    s = re.sub(r"[^\w\s-]", "", title).strip()
    return re.sub(r"[\s]+", " ", s)[:80] or "untitled"


def chronology_block(prev_title: str | None, next_title: str | None) -> str:
    """SEQUENTIAL link section (INGESTION_V2_FLOWS §5) — the chronological spine we inject
    OURSELVES from `order_index`, never the LLM. A note links to its predecessor within the
    same source (and successor, for walking forward); the first has no predecessor. Wikilink
    targets are the slugified titles = the created note filenames, so they resolve in Obsidian.
    Returns '' when there is neither neighbour (a lone note needs no sequence)."""
    if not prev_title and not next_title:
        return ""
    prev = f"[[{slugify(prev_title)}]]" if prev_title else "— (start of source)"
    parts = [f"Previous: {prev}"]
    if next_title:
        parts.append(f"Next: [[{slugify(next_title)}]]")
    return "## Sequence\n\n" + " · ".join(parts)
