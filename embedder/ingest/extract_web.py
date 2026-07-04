"""Web article extraction — trafilatura main-content (boilerplate stripped),
segmented per heading section. Fails loudly on JS-rendered SPAs (<200 chars).
"""
import html
import logging
import re

log = logging.getLogger("embedder.ingest.web")

MIN_CONTENT_CHARS = 200
_HEADING = re.compile(r"^#{1,4}\s+(.+)$", re.MULTILINE)

# Strip stray inline HTML trafilatura's markdown can leave (tables, <sub>/<sup>, <br>,
# comments). Curated tag list — so real HTML tags go, but prose like "a < b" or "Vec<T>"
# survives (T / 3 aren't tag names). Entities are then decoded to their characters.
_HTML_TAGS = (
    "a|abbr|address|area|article|aside|audio|b|bdi|bdo|blockquote|br|button|canvas|caption|"
    "cite|code|col|colgroup|data|datalist|dd|del|details|dfn|div|dl|dt|em|embed|fieldset|"
    "figcaption|figure|footer|form|h1|h2|h3|h4|h5|h6|header|hgroup|hr|i|iframe|img|input|ins|"
    "kbd|label|legend|li|main|map|mark|nav|noscript|object|ol|optgroup|option|output|p|picture|"
    "pre|q|s|samp|script|section|select|small|source|span|strong|style|sub|summary|sup|svg|"
    "table|tbody|td|template|textarea|tfoot|th|thead|time|tr|track|u|ul|var|video|wbr")
_HTML_TAG = re.compile(r"</?(?:" + _HTML_TAGS + r")\b[^>]*>", re.IGNORECASE)
_HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def strip_html_artifacts(text: str) -> str:
    """Remove residual HTML tags/comments and decode entities. Conservative: only real tag
    names match, so mathematical/code '<' in prose is preserved."""
    if not text:
        return text
    text = _HTML_COMMENT.sub("", text)
    text = _HTML_TAG.sub("", text)
    return html.unescape(text)


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
    md = strip_html_artifacts(md)   # scrub any inline HTML trafilatura left behind

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
