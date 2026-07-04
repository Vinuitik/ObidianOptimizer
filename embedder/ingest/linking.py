"""Semantic linking — SEQUENTIAL's companion (INGESTION_V2_FLOWS §5).  [NEW / v2]

We compute a note's SEMANTIC neighbours OURSELVES (never the LLM) by reusing the
embedder's EXISTING vault index: embed the note's own prose and ANN-query `note_chunks`
(`mcp_server._vector_candidates`, pgvector cosine — the same index `search_notes` /
`find_home_for_note` use) for the closest existing notes, then inject the top matches as
`[[wikilinks]]`.

Reuse, don't rebuild (the deliberate low-complexity choice):
  - The proposed note's OWN indexing — chunk + embed, re-chunk on edit, become
    searchable — rides the EXISTING note-embedding queue (`NoteEmbeddingWorker` picks up
    any `_inbox/` note whose `content_hash` changed; it is not excluded from embedding).
    Images are already a separate chunk source, so text-only indexing falls out for free.
    Nothing here touches that.
  - This module only computes OUTBOUND links (new note → already-indexed notes). Inbound
    links appear once the new note is itself embedded (Obsidian backlinks, automatic). A
    same-batch sibling isn't indexed yet, so it can't match here — which is fine, siblings
    are already joined by the SEQUENTIAL chain (`synthesize.chronology_block`).

Best-effort: any failure (DB down, cold index) yields NO links, never sinks a note.

*Tune:* `INGEST_SEMANTIC_FLOOR` / `INGEST_SEMANTIC_MAX_PER_NOTE`.
"""
from __future__ import annotations

import logging
import os
import re
from pathlib import PurePosixPath
from typing import Callable, Optional

log = logging.getLogger("embedder.ingest.linking")

# Cosine floor. Calibrated for this vault's EmbeddingGemma space (see search_notes:
# ≥0.5 strong, 0.4–0.5 related, ≤0.35 noise) — 0.45 keeps "related and up".
SEMANTIC_FLOOR = float(os.environ.get("INGEST_SEMANTIC_FLOOR", "0.45"))
SEMANTIC_MAX_PER_NOTE = int(os.environ.get("INGEST_SEMANTIC_MAX_PER_NOTE", "5"))
# Long (user-edited) notes are chunked at this grain before embedding; a ≤600-word Unit
# is one chunk. §5 computes links at chunk grain, aggregated to note grain.
SEMANTIC_CHUNK_CHARS = int(os.environ.get("INGEST_SEMANTIC_CHUNK_CHARS", "1500"))

_EMBED_RE = re.compile(r"!\[\[[^\]]+\]\]")            # media/image embeds
_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)   # ingest markers
_SCAFFOLD_RE = re.compile(r"^##\s+(Source|Sequence|Related)\b.*", re.MULTILINE)

EmbedFn = Callable[[list[str]], list[list[float]]]
CandidatesFn = Callable[[list[float]], list[tuple]]


def strip_for_query(body: str) -> str:
    """Reduce a note body to the PROSE we want to match on: drop media embeds, ingest
    HTML comments, and our own appended scaffolding (## Source / Sequence / Related), so
    the query vector reflects content, not links or images."""
    t = _COMMENT_RE.sub(" ", body)
    t = _EMBED_RE.sub(" ", t)
    m = _SCAFFOLD_RE.search(t)   # scaffolding is always appended at the end → cut from there
    if m:
        t = t[:m.start()]
    return t.strip()


def _chunks(text: str) -> list[str]:
    if len(text) <= SEMANTIC_CHUNK_CHARS:
        return [text] if text else []
    out: list[str] = []
    cur: list[str] = []
    n = 0
    for para in text.split("\n\n"):
        if cur and n + len(para) > SEMANTIC_CHUNK_CHARS:
            out.append("\n\n".join(cur))
            cur, n = [], 0
        cur.append(para)
        n += len(para)
    if cur:
        out.append("\n\n".join(cur))
    return out


def related_links(body: str, exclude_stems=(), embed_fn: Optional[EmbedFn] = None,
                  candidates_fn: Optional[CandidatesFn] = None,
                  floor: Optional[float] = None, cap: Optional[int] = None) -> list[str]:
    """Note prose → up to `cap` link STEMS (basenames) of the closest existing notes
    scoring ≥ `floor`. `exclude_stems` drops self + same-source siblings. Deterministic
    given the same index; `embed_fn`/`candidates_fn` are injectable for tests."""
    floor = SEMANTIC_FLOOR if floor is None else floor
    cap = SEMANTIC_MAX_PER_NOTE if cap is None else cap
    exclude = {s.lower() for s in exclude_stems}

    chunks = _chunks(strip_for_query(body))
    if not chunks:
        return []
    try:
        if embed_fn is None:
            from model_runtime import embed_texts
            embed_fn = lambda ts: embed_texts(ts, kind="query")   # note body is the QUERY side
        if candidates_fn is None:
            from mcp_server import _vector_candidates
            candidates_fn = _vector_candidates
        best: dict[str, float] = {}
        for vec in embed_fn(chunks):
            for row in candidates_fn(vec):
                note_path = row[0]
                sim = float(row[3]) if len(row) > 3 and row[3] is not None else 0.0
                if sim > best.get(note_path, -1.0):
                    best[note_path] = sim
    except Exception as e:   # DB down / cold index / embedder missing — links are optional
        log.warning("semantic linking skipped (%s)", e)
        return []

    out: list[str] = []
    for note_path, sim in sorted(best.items(), key=lambda kv: kv[1], reverse=True):
        if sim < floor:
            continue
        stem = PurePosixPath(note_path).stem
        low = stem.lower()
        if low in exclude or any(low == o.lower() for o in out):
            continue
        out.append(stem)
        if len(out) >= cap:
            break
    return out


def related_block(stems: list[str]) -> str:
    """The `## Related` section (empty string when there are no matches)."""
    if not stems:
        return ""
    return "## Related\n\n" + " · ".join(f"[[{s}]]" for s in stems)


# ── probe-driven semantic linking (live v1 path) ──────────────────────────────
# The user's spec (differs from related_links above, which is the v2 ANN variant):
#   per note → LLM writes a few QUESTIONS/STATEMENTS about it → each is a QUERY run
#   through the HYBRID search (search_notes: BM25 + pgvector, RRF) → take the top N
#   results overall → append as bare [[wikilinks]] at the very end of the note.
# Adjustable: INGEST_SEMANTIC_LINKS (how many links), INGEST_SEMANTIC_PROBES (how
# many queries). Best-effort: any failure (LLM/DB down) → no links, note still saved.
SEMANTIC_LINKS = int(os.environ.get("INGEST_SEMANTIC_LINKS", "2"))
SEMANTIC_PROBES = int(os.environ.get("INGEST_SEMANTIC_PROBES", "3"))
SEMANTIC_SEARCH_LIMIT = int(os.environ.get("INGEST_SEMANTIC_SEARCH_LIMIT", "5"))

CompleteFn = Callable[[str, str], str]
SearchFn = Callable[[str, int], list]


def _parse_str_array(text: str) -> list[str]:
    """Pull a JSON array of strings out of an LLM reply (tolerates ``` fences / prose)."""
    import json
    t = text.strip()
    lo, hi = t.find("["), t.rfind("]")
    if not (0 <= lo < hi):
        return []
    try:
        arr = json.loads(t[lo:hi + 1])
    except json.JSONDecodeError:
        return []
    return [str(x).strip() for x in arr if isinstance(x, (str, int, float)) and str(x).strip()]


def generate_probes(title: str, body: str, n: Optional[int] = None,
                    complete_fn: Optional[CompleteFn] = None) -> list[str]:
    """LLM → up to `n` short questions/statements about the note, used ONLY as search
    queries (never linked directly). `complete_fn` injectable for tests."""
    n = SEMANTIC_PROBES if n is None else n
    if n <= 0:
        return []
    prose = strip_for_query(body)
    if not prose:
        return []
    if complete_fn is None:
        from ingest.synthesize import _complete
        complete_fn = _complete
    system = ("You generate concise SEARCH QUERIES to find related existing notes in a "
              "knowledge vault. Output ONLY a JSON array of strings — each a short question "
              "or statement capturing a key idea of the note. No prose, no code fences.")
    prompt = (f"Note title: {title}\n\nNote body:\n{prose[:2000]}\n\n"
              f"Return {n} search queries as a JSON array of strings.")
    try:
        return _parse_str_array(complete_fn(prompt, system))[:n]
    except Exception as e:   # LLM/wrapper down — links are optional, never fatal
        log.warning("probe generation skipped (%s)", e)
        return []


def semantic_link_stems(title: str, body: str, exclude_stems=(), cap: Optional[int] = None,
                        complete_fn: Optional[CompleteFn] = None,
                        search_fn: Optional[SearchFn] = None) -> list[str]:
    """Note → up to `cap` link STEMS: LLM probes → hybrid `search_notes` per probe →
    aggregate by best (lowest) rank across probes → top `cap` after excluding self +
    same-source siblings. `search_fn`/`complete_fn` injectable for tests."""
    cap = SEMANTIC_LINKS if cap is None else cap
    if cap <= 0:
        return []
    exclude = {s.lower() for s in exclude_stems}
    probes = generate_probes(title, body, complete_fn=complete_fn)
    if not probes:
        return []
    if search_fn is None:
        from mcp_server import search_notes
        search_fn = search_notes

    best_rank: dict[str, int] = {}   # note_path → best position seen across all probes
    for q in probes:
        try:
            hits = search_fn(q, SEMANTIC_SEARCH_LIMIT)
        except Exception as e:   # DB/index down — skip this probe, keep going
            log.warning("semantic search skipped for %r (%s)", q, e)
            continue
        for rank, hit in enumerate(hits):
            path = (hit.get("notePath") or hit.get("note_path")) if isinstance(hit, dict) else None
            if not path:
                continue
            if path not in best_rank or rank < best_rank[path]:
                best_rank[path] = rank

    out: list[str] = []
    for path, _ in sorted(best_rank.items(), key=lambda kv: kv[1]):
        stem = PurePosixPath(path).stem
        low = stem.lower()
        if low in exclude or any(low == o.lower() for o in out):
            continue
        out.append(stem)
        if len(out) >= cap:
            break
    return out


def append_links(note_md: str, link_stems) -> str:
    """Append bare `[[stem]]` links, one per line, at the VERY END of the note (after
    everything, including #review) — deterministic, verbatim, deduped. No-op if empty."""
    seen, uniq = set(), []
    for s in link_stems:
        if s and s.lower() not in seen:
            seen.add(s.lower())
            uniq.append(s)
    if not uniq:
        return note_md
    return note_md.rstrip() + "\n\n" + "\n".join(f"[[{s}]]" for s in uniq) + "\n"
