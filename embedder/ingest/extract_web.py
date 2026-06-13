"""Web article extraction — trafilatura main-content (boilerplate stripped),
segmented per heading section. Fails loudly on JS-rendered SPAs (<200 chars).
"""
import logging
import re

log = logging.getLogger("embedder.ingest.web")

MIN_CONTENT_CHARS = 200
_HEADING = re.compile(r"^#{1,4}\s+(.+)$", re.MULTILINE)


def extract(ref: str) -> dict:
    import trafilatura

    downloaded = trafilatura.fetch_url(ref)
    if not downloaded:
        raise RuntimeError(f"fetch failed: {ref}")
    md = trafilatura.extract(downloaded, output_format="markdown",
                             include_images=False, include_links=False)
    if not md or len(md) < MIN_CONTENT_CHARS:
        raise RuntimeError(
            f"main content too thin ({len(md or '')} chars) — likely a "
            f"JS-rendered SPA; no headless browser in scope (tech notes)")
    meta = trafilatura.extract_metadata(downloaded)
    title = (meta.title if meta and meta.title else ref)

    return {
        "source": {"type": "web", "ref": ref, "title": title,
                   "duration_s": 0, "chapters": []},
        "segments": _split_by_heading(md),
        "media": [],
    }


def _split_by_heading(md: str) -> list[dict]:
    """One segment per heading section; preamble (before first heading) first."""
    matches = list(_HEADING.finditer(md))
    if not matches:
        return [{"loc": {"heading": ""}, "text": md.strip()}]
    segments = []
    pre = md[:matches[0].start()].strip()
    if pre:
        segments.append({"loc": {"heading": ""}, "text": pre})
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(md)
        body = md[m.start():end].strip()
        if body:
            segments.append({"loc": {"heading": m.group(1).strip()}, "text": body})
    return segments
