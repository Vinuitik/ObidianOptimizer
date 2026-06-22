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
    pass


# ── LLM plumbing ─────────────────────────────────────────────────────────

def _complete(prompt: str, system: str) -> str:
    resp = httpx.post(f"{WRAPPER_URL}/complete",
                      json={"prompt": prompt, "system": system,
                            "model": SYNTH_MODEL},
                      timeout=LLM_TIMEOUT_S)
    if resp.status_code != 200:
        raise SynthesisError(f"wrapper /complete {resp.status_code}: {resp.text[:300]}")
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
    prompt = OUTLINE_PROMPT.format(
        title=bundle["source"].get("title", "untitled"),
        source_type=bundle["source"]["type"],
        chapters=[c.get("title") for c in bundle["source"].get("chapters", [])] or "none",
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
    ts = [s["loc"]["t_start"] for s in segs if "t_start" in s.get("loc", {})]
    pages = sorted({s["loc"]["page"] for s in segs if "page" in s.get("loc", {})})
    if ts and ref.startswith("http"):
        t0 = int(min(ts))
        sep = "&" if "?" in ref else "?"
        lines[1] = f"[{bundle_util._fmt_ts(t0)}]({ref}{sep}t={t0}s) · {ref}"
    elif ts:
        lines.append(f"from {bundle_util._fmt_ts(min(ts))}")
    if pages:
        lines.append("pages: " + ", ".join(str(p) for p in pages))
    return "\n".join(lines)


def slugify(title: str) -> str:
    s = re.sub(r"[^\w\s-]", "", title).strip()
    return re.sub(r"[\s]+", " ", s)[:80] or "untitled"
