"""Detect videos EMBEDDED in a web page's MAIN CONTENT (Prong A).

A lesson/article page's substance is often an embedded player, which trafilatura strips as
non-prose — so the pipeline would keep the 572-char blurb and silently drop the video. This
finds the real content videos so `jobs` can transcribe them as part of the same capture
(page text + each video → subnotes under one source).

Design (deliberately simple — see FLOWS "Prong A"):
  • MAIN CONTENT only: nav / aside / header / footer subtrees are dropped before scanning, and
    we match PLAYER elements (`<iframe>`, `<video>/<source>`) + `og:video`, NOT bare `<a>`
    links — so incidental "further reading" YouTube links in prose are ignored.
  • Appearance order, deduped, capped (`MAX_VIDEOS`) so a link-farm page can't fan out forever.
  • URLs normalized to a canonical, yt-dlp-resolvable form (embed/… → watch?v=…).

Pure: `detect_content_videos(html, base_url)` does NO network — the caller already fetched the
HTML. Returns a list of canonical video URLs.
"""
import re
from urllib.parse import urljoin

MAX_VIDEOS = 3          # per page; the rest are noted as skipped, not ingested
_STRIP_TAGS = ("nav", "aside", "header", "footer")

# host/pattern → canonical URL. Each returns a yt-dlp-resolvable URL or None (not a video).
_YT_ID = re.compile(r"(?:youtube(?:-nocookie)?\.com/(?:embed|v|shorts)/|youtu\.be/|youtube\.com/watch\?v=)([\w-]{11})", re.I)
_VIMEO_ID = re.compile(r"(?:player\.)?vimeo\.com/(?:video/)?(\d+)", re.I)
_IG_ID = re.compile(r"instagram\.com/(?:reel|reels|p|tv)/([\w-]+)", re.I)
_DIRECT = re.compile(r"\.(?:mp4|webm|mov|m4v)(?:\?|#|$)", re.I)


def canonical_video_url(url: str) -> str | None:
    """A player src / og:video URL → a canonical yt-dlp-resolvable URL, or None if it isn't a
    recognizable video (e.g. an ad iframe, a tweet embed with no video)."""
    if not url:
        return None
    m = _YT_ID.search(url)
    if m:
        return f"https://www.youtube.com/watch?v={m.group(1)}"
    m = _VIMEO_ID.search(url)
    if m:
        return f"https://vimeo.com/{m.group(1)}"
    m = _IG_ID.search(url)
    if m:
        return f"https://www.instagram.com/reel/{m.group(1)}/"
    if _DIRECT.search(url):
        return url                      # direct media file (already absolute-ized by caller)
    return None


def detect_content_videos(html: str, base_url: str = "") -> list[str]:
    """Canonical URLs of videos embedded in the page's MAIN content, appearance order, capped.
    Empty when the page is plain text. Never raises — a parse failure just yields []."""
    try:
        import lxml.html as LH
    except Exception:
        return []
    try:
        doc = LH.fromstring(html)
    except Exception:
        return []

    found: list[str] = []

    # og:video is the page's own declaration of its primary video — trust it first.
    for meta in doc.xpath('//meta[@property="og:video" or @property="og:video:url" '
                          'or @property="og:video:secure_url"]'):
        if (u := canonical_video_url((meta.get("content") or "").strip())):
            found.append(u)

    # Drop chrome so a sidebar/footer player doesn't count as content.
    for tag in _STRIP_TAGS:
        for el in doc.xpath(f"//{tag}"):
            el.getparent().remove(el) if el.getparent() is not None else None

    # Player elements in the remaining (main) content, in document order.
    for el in doc.iter("iframe", "video", "source"):
        src = el.get("src") or el.get("data-src") or ""
        if src and base_url:
            src = urljoin(base_url, src)
        if (u := canonical_video_url(src)):
            found.append(u)

    # Dedup preserving order, then cap.
    seen, out = set(), []
    for u in found:
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out[:MAX_VIDEOS]
