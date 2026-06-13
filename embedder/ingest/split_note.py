"""Post-factum note splitter — "ingest where the extractor is the note itself".

Oversized note → heading-based segments → reuse the synthesis OUTLINE pass →
WRITE pass per child → original rewritten as a hub (deterministic template).
All writes via the Java internal API.
"""
import logging
import os
import re

from ingest import publish, synthesize
from ingest import bundle as bundle_util

log = logging.getLogger("embedder.ingest.split")

SPLIT_MIN_CHARS = int(os.environ.get("SPLIT_MIN_CHARS", "6000"))
_HEADING = re.compile(r"^#{1,3}\s+(.+)$", re.MULTILINE)
_FM = re.compile(r"^---\n.*?\n---\n", re.DOTALL)


def split(note_rel_path: str, content: str) -> dict:
    body = _FM.sub("", content, count=1)
    if len(body) < SPLIT_MIN_CHARS:
        raise ValueError(
            f"note is {len(body)} chars; splitting below {SPLIT_MIN_CHARS} is noise "
            f"(SPLIT_MIN_CHARS)")

    title = note_rel_path.rsplit("/", 1)[-1].removesuffix(".md")
    bundle = {
        "source": {"type": "note", "ref": note_rel_path, "title": title,
                   "duration_s": 0, "chapters": []},
        "segments": _segment_note(body),
        "media": [],
    }

    plans = synthesize.outline(bundle)
    if len(plans) < 2:
        raise ValueError("outline produced a single note — nothing to split")

    folder = note_rel_path.rsplit("/", 1)[0] if "/" in note_rel_path else ""
    numbered = bundle_util.number_segments(bundle)
    children = []
    for plan in plans:
        note_md = synthesize.write_note(bundle, plan, numbered)
        child_title = synthesize.slugify(plan["title"])
        path = publish.create_note(folder, child_title, note_md)
        children.append({"title": child_title, "path": path,
                         "hint": plan.get("summary_hint", "")})

    publish.update_note(note_rel_path, _hub(content, title, children))
    return {"children": children}


def _segment_note(body: str) -> list[dict]:
    """Heading sections as segments; preserves embeds inside their section."""
    matches = list(_HEADING.finditer(body))
    if not matches:
        # no headings: paragraph-window fallback
        paras = [p for p in body.split("\n\n") if p.strip()]
        return [{"loc": {"heading": f"part {i}"}, "text": p}
                for i, p in enumerate(paras)]
    segments = []
    pre = body[:matches[0].start()].strip()
    if pre:
        segments.append({"loc": {"heading": ""}, "text": pre})
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        text = body[m.start():end].strip()
        if text:
            segments.append({"loc": {"heading": m.group(1).strip()}, "text": text})
    return segments


def _hub(original: str, title: str, children: list[dict]) -> str:
    """Original becomes a table of contents — frontmatter preserved verbatim."""
    fm_match = _FM.match(original)
    fm = fm_match.group(0) if fm_match else "---\n---\n"
    lines = [fm, f"\n# {title}\n", "Split into focused notes:\n"]
    for c in children:
        hint = f" — {c['hint']}" if c["hint"] else ""
        lines.append(f"- [[{c['title']}]]{hint}")
    lines.append("\n#review\n")
    return "\n".join(lines)
