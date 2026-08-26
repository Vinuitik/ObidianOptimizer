"""Subscriptions discovery — Step 5 of the Subscriptions feature.

Purely stateless: given a source (YouTube channel URL or blog/feed URL), list what's
CURRENTLY there. No DB, no dedup — a later Java step diffs candidates against what's
already been ingested.

Two source kinds:
  • youtube_channel — reuses download.downloader.list_playlist_entries (yt-dlp
    extract_flat already resolves /channel/UC…, /@handle, /c/name as a playlist of
    uploads, no separate channel-id resolution needed).
  • feed            — RSS/Atom via feedparser, with lightweight regex-over-HTML
    autodiscovery (same low-dependency convention as ingest/embed_detect.py's
    canonical_video_url — no BeautifulSoup/lxml added just for one <link> tag) when
    the given URL is a homepage rather than a direct feed URL.
"""
import re
from urllib.parse import urljoin

import feedparser
import httpx

from download.downloader import list_playlist_entries

# A <link rel="alternate" type="application/(rss|atom)+xml" href="..."> tag, in either
# attribute order — real-world HTML has both. `[^>]*?` between anchors keeps it non-greedy
# so it doesn't stretch across multiple <link> tags.
_FEED_LINK_RE = re.compile(
    r'<link\b[^>]*?rel=["\']alternate["\'][^>]*?type=["\']application/(?:rss|atom)\+xml["\'][^>]*?href=["\']([^"\']+)["\']'
    r'|<link\b[^>]*?type=["\']application/(?:rss|atom)\+xml["\'][^>]*?rel=["\']alternate["\'][^>]*?href=["\']([^"\']+)["\']'
    r'|<link\b[^>]*?href=["\']([^"\']+)["\'][^>]*?rel=["\']alternate["\'][^>]*?type=["\']application/(?:rss|atom)\+xml["\']'
    r'|<link\b[^>]*?rel=["\']alternate["\'][^>]*?href=["\']([^"\']+)["\'][^>]*?type=["\']application/(?:rss|atom)\+xml["\']',
    re.IGNORECASE,
)

_FALLBACK_PATHS = ("/feed", "/feed/", "/rss.xml", "/atom.xml", "/rss", "/index.xml")


def discover_youtube_channel(url: str, limit: int = 20) -> list[dict]:
    """[{"item_url": ..., "title": ..., "published_at": None}, ...] — published_at is
    unreliable from flat-playlist extraction (a known yt-dlp limitation), so it's
    always None here; recency is handled by dedup elsewhere, not a date cutoff."""
    entries = list_playlist_entries(url, limit=limit)
    return [{"item_url": e["url"], "title": e["title"], "published_at": None} for e in entries]


def find_feed_url(homepage_url: str) -> str | None:
    """<link rel="alternate" type="application/(rss|atom)+xml" href="..."> in the
    homepage's HTML (either attribute order), else probe common feed paths via HEAD.
    Timeouts/errors on any single probe just move to the next candidate. None if
    nothing resolves."""
    try:
        resp = httpx.get(homepage_url, timeout=10.0, follow_redirects=True)
        m = _FEED_LINK_RE.search(resp.text)
        if m:
            href = next(g for g in m.groups() if g)
            return urljoin(homepage_url, href)
    except Exception:
        pass

    for path in _FALLBACK_PATHS:
        candidate = urljoin(homepage_url, path)
        try:
            resp = httpx.head(candidate, timeout=5.0, follow_redirects=True)
            content_type = resp.headers.get("content-type", "").lower()
            if resp.status_code < 400 and ("xml" in content_type or "rss" in content_type):
                return candidate
        except Exception:
            continue
    return None


def discover_feed(feed_url: str) -> list[dict]:
    """feedparser.parse(feed_url) -> [{"item_url", "title", "published_at"}, ...].
    Raises ValueError if feedparser reports bozo AND there are zero entries — a bozo
    flag alone isn't fatal (many real feeds set it on minor XML quirks but still
    parse fine); only a hard failure when there's nothing usable."""
    parsed = feedparser.parse(feed_url)
    if parsed.get("bozo") and not parsed.entries:
        raise ValueError(f"malformed feed, no entries: {feed_url}")
    return [
        {
            "item_url": e.get("link"),
            "title": e.get("title"),
            "published_at": e.get("published"),
        }
        for e in parsed.entries
    ]


def discover(source_url: str, source_type: str) -> tuple[list[dict], str | None]:
    """Dispatch by source_type. Returns (candidates, resolved_feed_url) —
    resolved_feed_url is None for youtube_channel, and for feed it's None only when
    source_url was already a working feed (no autodiscovery needed).

    Feed branch: try source_url directly as a feed first (most pasted URLs already
    are one) — avoids an extra HTML fetch. Only on failure (raise, or zero entries)
    fall back to find_feed_url() treating source_url as a homepage, then retry.
    """
    if source_type == "youtube_channel":
        return discover_youtube_channel(source_url), None
    if source_type == "feed":
        try:
            candidates = discover_feed(source_url)
            if candidates:
                return candidates, None
        except ValueError:
            pass
        resolved = find_feed_url(source_url)
        if resolved is None:
            raise ValueError(f"no feed found for: {source_url}")
        return discover_feed(resolved), resolved
    raise ValueError(f"unknown source_type: {source_type}")
