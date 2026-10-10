"""Subscriptions discovery — Step 5 of the Subscriptions feature (P3: + page_watch,
youtube_posts).

Purely stateless: given a source (YouTube channel URL, blog/feed URL, arbitrary page,
or a YouTube channel's Community tab), list what's CURRENTLY there. No DB, no dedup —
a later Java step diffs candidates against what's already been ingested (feed_items.
item_url UNIQUE) — this is also how page_watch's "diffing" works: this module always
returns the full current snapshot, and the caller's dedup makes a second poll of an
unchanged page a no-op.

Four source kinds:
  • youtube_channel — reuses download.downloader.list_playlist_entries (yt-dlp
    extract_flat already resolves /channel/UC…, /@handle, /c/name as a playlist of
    uploads, no separate channel-id resolution needed).
  • feed            — RSS/Atom via feedparser, with lightweight regex-over-HTML
    autodiscovery (same low-dependency convention as ingest/embed_detect.py's
    canonical_video_url — no BeautifulSoup/lxml added just for one <link> tag) when
    the given URL is a homepage rather than a direct feed URL.
  • page_watch      — no feed, no RSS: snapshot every same-site outbound link on the
    page (regex over the raw HTML, same convention as above — no JS rendering). The
    Java caller's existing dedup-by-item_url does the actual "diffing".
  • youtube_posts   — a YouTube channel's Community tab (`/posts`) has no RSS. Fetch
    the tab's static HTML and parse the embedded `ytInitialData` JSON blob for
    `backstagePostRenderer` entries (spiked 2026-10-05: works with a plain GET + a
    CONSENT cookie, no login/JS rendering needed — see feed/FLOWS.md Technology Notes
    for the failure mode if YouTube ever changes this page's shape).
"""
import html
import json
import re
from urllib.parse import urljoin, urlparse

import feedparser
import httpx
import yt_dlp

from download.downloader import list_playlist_entries

_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

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


def _parse_duration(raw) -> int | None:
    """itunes:duration -> seconds. Accepts '3600', '59:30', '1:02:03'. None if unparseable."""
    if not raw:
        return None
    try:
        parts = [int(p) for p in str(raw).strip().split(":")]
    except ValueError:
        return None
    secs = 0
    for p in parts:
        secs = secs * 60 + p
    return secs


def _entry_thumb(e) -> str | None:
    """media:thumbnail (YouTube RSS, many blogs) -> first url, else None."""
    thumbs = e.get("media_thumbnail")
    if thumbs and isinstance(thumbs, list) and isinstance(thumbs[0], dict):
        return thumbs[0].get("url")
    return None


def discover_feed(feed_url: str) -> list[dict]:
    """feedparser.parse(feed_url) -> [{"item_url", "title", "published_at", "thumb_url",
    "duration_s"}, ...]. Raises ValueError if it is NOT a feed: bozo with zero entries
    (malformed / HTML) or zero entries with no feed version. A VALID feed with zero
    entries (e.g. arXiv on a weekend) returns [] — empty is not a failure."""
    parsed = feedparser.parse(feed_url)
    if not parsed.entries:
        if parsed.get("bozo") or not parsed.get("version"):
            raise ValueError(f"malformed feed, no entries: {feed_url}")
        return []
    return [
        {
            "item_url": e.get("link"),
            "title": e.get("title"),
            "published_at": e.get("published"),
            "thumb_url": _entry_thumb(e),
            "duration_s": _parse_duration(e.get("itunes_duration")),
        }
        for e in parsed.entries
    ]


# href in either quote style, text run captured non-greedily (DOTALL off — a real <a> tag
# doesn't span lines in practice, and this avoids pathological backtracking on malformed HTML).
_ANCHOR_RE = re.compile(r'<a\b[^>]*?href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', re.IGNORECASE)
_TAG_RE = re.compile(r'<[^>]+>')


def _link_text(raw: str) -> str:
    return html.unescape(_TAG_RE.sub('', raw)).strip()


def _same_site(host_a: str, host_b: str) -> bool:
    strip_www = lambda h: h[4:] if h.startswith("www.") else h
    return strip_www(host_a) == strip_www(host_b)


def discover_page_watch(url: str, limit: int = 50) -> list[dict]:
    """No feed, no RSS: snapshot every same-site outbound link currently on the page.
    [{"item_url", "title", "published_at": None, "thumb_url": None, "duration_s": None}, ...]
    deduped by URL, in document order, capped at `limit`. The Java caller's existing
    item_url-dedup is what turns this into "diffing" — an unchanged page just re-offers
    the same URLs, all of which already exist, so the net result is 0 new items (per
    plan metric). Raises ValueError only if the page itself can't be fetched; a page
    with zero same-site links returns [] (not a failure, same convention as an empty feed)."""
    try:
        resp = httpx.get(url, headers={"User-Agent": _UA}, timeout=10.0, follow_redirects=True)
    except Exception as e:
        raise ValueError(f"could not fetch page: {url}: {e}")
    base_host = (urlparse(url).hostname or "").lower()
    seen: set[str] = set()
    out: list[dict] = []
    for href, text in _ANCHOR_RE.findall(resp.text):
        if href.startswith(("#", "javascript:", "mailto:")):
            continue
        target = urljoin(url, href).split("#", 1)[0]
        host = (urlparse(target).hostname or "").lower()
        if not host or not _same_site(host, base_host) or target in seen:
            continue
        seen.add(target)
        title = _link_text(text) or target
        out.append({"item_url": target, "title": title, "published_at": None,
                    "thumb_url": None, "duration_s": None})
        if len(out) >= limit:
            break
    return out


_YT_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
_YT_FEED = "https://www.youtube.com/feeds/videos.xml?channel_id="


def _youtube_channel_id(url: str) -> str | None:
    """Channel id for a channel / @handle / video URL via yt-dlp (1 flat entry, no download)."""
    opts = {"extract_flat": "in_playlist", "skip_download": True, "quiet": True,
            "no_warnings": True, "playlistend": 1, "noplaylist": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False) or {}
    cid = str(info.get("channel_id") or "")
    return cid if cid.startswith("UC") else None


def youtube_feed_url(url: str) -> str | None:
    """Any YouTube channel/@handle//c//user/video URL -> its RSS feed URL. None if the URL
    is not YouTube or is a playlist page (handled by the yt-dlp playlist path). Raises
    ValueError if it is YouTube but the channel can't be resolved."""
    u = urlparse(url)
    if (u.hostname or "").lower() not in _YT_HOSTS:
        return None
    if u.path.startswith("/feeds/videos.xml"):
        return url
    if u.path.startswith("/playlist"):
        return None
    m = re.match(r"^/channel/(UC[\w-]+)", u.path)
    if m:
        return _YT_FEED + m.group(1)
    try:
        cid = _youtube_channel_id(url)
    except Exception as e:
        raise ValueError(f"could not resolve YouTube channel for {url}: {e}")
    if not cid:
        raise ValueError(f"could not resolve YouTube channel for {url}")
    return _YT_FEED + cid


# Bypasses YouTube's EU consent-page redirect (a plain GET with no cookie 302s to
# consent.youtube.com, which never reaches ytInitialData) — a well-known, stable
# workaround (no login, no JS). See feed/FLOWS.md Technology Notes.
_CONSENT_COOKIES = {"CONSENT": "YES+1"}
_YT_INITIAL_DATA_RE = re.compile(r'var ytInitialData\s*=\s*(\{.*?\});</script>', re.DOTALL)


def _walk_backstage_posts(obj) -> list[dict]:
    """Recursively find every backstagePostRenderer block in the ytInitialData tree
    (Community posts are nested arbitrarily deep inside the page's render tree — there's
    no fixed path, so a walk is the only robust approach)."""
    found: list[dict] = []
    if isinstance(obj, dict):
        post = obj.get("backstagePostRenderer")
        if isinstance(post, dict):
            found.append(post)
        for v in obj.values():
            found.extend(_walk_backstage_posts(v))
    elif isinstance(obj, list):
        for v in obj:
            found.extend(_walk_backstage_posts(v))
    return found


def discover_youtube_posts(posts_url: str, limit: int = 20) -> list[dict]:
    """Poll a YouTube channel's Community tab (`/@handle/posts`): fetch the static HTML
    and parse the embedded `ytInitialData` JSON for `backstagePostRenderer` blocks — no
    RSS exists for this, and no JS rendering is used (spiked 2026-10-05: a plain GET with
    a CONSENT cookie already returns the full post list in static HTML).
    [{"item_url": "https://www.youtube.com/post/<id>", "title": <first line of the post>,
      "published_at": None,  # publishedTimeText is relative ("3 days ago"), not a parseable instant
      "thumb_url": None, "duration_s": None, "body": <full post text>}, ...]
    Raises ValueError if the page is unreachable OR ytInitialData isn't found — the
    latter means YouTube changed this page's shape or started blocking it; this is a
    report-not-hack boundary, not a parsing bug to patch around blindly."""
    try:
        resp = httpx.get(posts_url, headers={"User-Agent": _UA}, cookies=_CONSENT_COOKIES,
                         timeout=10.0, follow_redirects=True)
    except Exception as e:
        raise ValueError(f"could not fetch {posts_url}: {e}")
    m = _YT_INITIAL_DATA_RE.search(resp.text)
    if not m:
        raise ValueError(f"no ytInitialData found at {posts_url} (page shape changed or blocked)")
    try:
        data = json.loads(m.group(1))
    except Exception as e:
        raise ValueError(f"ytInitialData did not parse for {posts_url}: {e}")

    out: list[dict] = []
    for post in _walk_backstage_posts(data)[:limit]:
        post_id = post.get("postId")
        if not post_id:
            continue
        runs = post.get("contentText", {}).get("runs", [])
        body = "".join(r.get("text", "") for r in runs).strip()
        title = next((line for line in body.splitlines() if line.strip()), "Community post")
        out.append({
            "item_url": f"https://www.youtube.com/post/{post_id}",
            "title": title[:120],
            "published_at": None,
            "thumb_url": None,
            "duration_s": None,
            "body": body,
        })
    return out


def discover(source_url: str, source_type: str) -> tuple[list[dict], str | None]:
    """Dispatch by source_type. Returns (candidates, resolved_feed_url) —
    resolved_feed_url is None for youtube_channel, and for feed it's None only when
    source_url was already a working feed (no autodiscovery needed).

    Feed branch: try source_url directly as a feed first (most pasted URLs already
    are one) — avoids an extra HTML fetch. Only on failure (raise, or zero entries)
    fall back to find_feed_url() treating source_url as a homepage, then retry.
    """
    if source_type in ("youtube_channel", "feed"):
        yt_feed = youtube_feed_url(source_url)
        if yt_feed:
            return discover_feed(yt_feed), yt_feed
    if source_type == "youtube_channel":
        return discover_youtube_channel(source_url), None
    if source_type == "feed":
        try:
            return discover_feed(source_url), None   # valid-but-empty feed is OK ([])
        except ValueError:
            pass
        resolved = find_feed_url(source_url)
        if resolved is None:
            raise ValueError(f"no feed found for: {source_url}")
        return discover_feed(resolved), resolved
    if source_type == "page_watch":
        return discover_page_watch(source_url), None
    if source_type == "youtube_posts":
        return discover_youtube_posts(source_url), None
    raise ValueError(f"unknown source_type: {source_type}")
