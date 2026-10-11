"""Subscriptions discovery tests (subscriptions/discover.py) — Step 5 of the
Subscriptions feature. No real network: list_playlist_entries, httpx.get/head, and
feedparser.parse are all mocked."""
import json
import types

import pytest
from fastapi.testclient import TestClient

import main as embedder_main
from subscriptions import discover


@pytest.fixture
def client():
    return TestClient(embedder_main.app, raise_server_exceptions=True)


# ---------------------------------------------------------------------------
# discover_youtube_channel
# ---------------------------------------------------------------------------

def test_discover_youtube_channel_maps_entries(monkeypatch):
    captured = {}

    def fake_list_playlist_entries(url, limit=None):
        captured["url"] = url
        captured["limit"] = limit
        return [
            {"url": "https://youtu.be/a", "title": "Video A"},
            {"url": "https://youtu.be/b", "title": "Video B"},
        ]
    monkeypatch.setattr(discover, "list_playlist_entries", fake_list_playlist_entries)

    out = discover.discover_youtube_channel("https://www.youtube.com/@someone", limit=5)

    assert out == [
        {"item_url": "https://youtu.be/a", "title": "Video A", "published_at": None},
        {"item_url": "https://youtu.be/b", "title": "Video B", "published_at": None},
    ]
    assert captured["url"] == "https://www.youtube.com/@someone"
    assert captured["limit"] == 5


def test_discover_youtube_channel_default_limit(monkeypatch):
    captured = {}

    def fake_list_playlist_entries(url, limit=None):
        captured["limit"] = limit
        return []
    monkeypatch.setattr(discover, "list_playlist_entries", fake_list_playlist_entries)
    discover.discover_youtube_channel("https://www.youtube.com/@someone")
    assert captured["limit"] == 20


# ---------------------------------------------------------------------------
# find_feed_url
# ---------------------------------------------------------------------------

def _fake_httpx_get(html, status_code=200):
    def fake_get(url, timeout=None, follow_redirects=None, headers=None, cookies=None):
        return types.SimpleNamespace(text=html, status_code=status_code, headers={})
    return fake_get


def test_find_feed_url_resolves_rel_before_type(monkeypatch):
    html = '<html><head><link rel="alternate" type="application/rss+xml" href="/feed.xml"></head></html>'
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get(html))
    assert discover.find_feed_url("https://example.com/") == "https://example.com/feed.xml"


def test_find_feed_url_resolves_type_before_rel(monkeypatch):
    html = '<html><head><link type="application/atom+xml" rel="alternate" href="https://example.com/atom.xml"></head></html>'
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get(html))
    assert discover.find_feed_url("https://example.com/") == "https://example.com/atom.xml"


def test_find_feed_url_falls_back_to_path_probe(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get("<html><head></head></html>"))

    def fake_head(url, timeout=None, follow_redirects=None):
        if url == "https://example.com/feed":
            return types.SimpleNamespace(status_code=200, headers={"content-type": "application/rss+xml"})
        return types.SimpleNamespace(status_code=404, headers={})
    monkeypatch.setattr(discover.httpx, "head", fake_head)

    assert discover.find_feed_url("https://example.com/") == "https://example.com/feed"


def test_find_feed_url_returns_none_when_nothing_resolves(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get("<html><head></head></html>"))
    monkeypatch.setattr(discover.httpx, "head",
                        lambda url, timeout=None, follow_redirects=None: types.SimpleNamespace(status_code=404, headers={}))
    assert discover.find_feed_url("https://example.com/") is None


# ---------------------------------------------------------------------------
# discover_feed
# ---------------------------------------------------------------------------

def _fake_parsed(entries, bozo=False):
    return types.SimpleNamespace(
        get=lambda k, default=None: bozo if k == "bozo" else default,
        entries=entries,
    )


def test_discover_feed_returns_entries(monkeypatch):
    entries = [
        {"link": "https://example.com/p1", "title": "Post 1", "published": "2026-08-01T00:00:00Z"},
        {"link": "https://example.com/p2", "title": "Post 2"},
    ]

    class FakeEntry(dict):
        def get(self, k, default=None):
            return dict.get(self, k, default)
    fake_entries = [FakeEntry(e) for e in entries]

    monkeypatch.setattr(discover.feedparser, "parse", lambda url: _fake_parsed(fake_entries))

    out = discover.discover_feed("https://example.com/feed.xml")
    assert out == [
        {"item_url": "https://example.com/p1", "title": "Post 1", "published_at": "2026-08-01T00:00:00Z",
         "thumb_url": None, "duration_s": None},
        {"item_url": "https://example.com/p2", "title": "Post 2", "published_at": None,
         "thumb_url": None, "duration_s": None},
    ]


def test_discover_feed_raises_on_bozo_with_no_entries(monkeypatch):
    monkeypatch.setattr(discover.feedparser, "parse", lambda url: _fake_parsed([], bozo=True))
    with pytest.raises(ValueError, match="malformed feed"):
        discover.discover_feed("https://example.com/feed.xml")


def test_discover_feed_bozo_but_has_entries_does_not_raise(monkeypatch):
    class FakeEntry(dict):
        def get(self, k, default=None):
            return dict.get(self, k, default)
    fake_entries = [FakeEntry({"link": "https://example.com/p1", "title": "Post 1"})]
    monkeypatch.setattr(discover.feedparser, "parse", lambda url: _fake_parsed(fake_entries, bozo=True))

    out = discover.discover_feed("https://example.com/feed.xml")
    assert out == [{"item_url": "https://example.com/p1", "title": "Post 1", "published_at": None,
                    "thumb_url": None, "duration_s": None}]


# ---------------------------------------------------------------------------
# discover — dispatch
# ---------------------------------------------------------------------------

def test_discover_dispatches_to_youtube_channel(monkeypatch):
    monkeypatch.setattr(discover, "discover_youtube_channel",
                        lambda url, limit=20: [{"item_url": "u", "title": "t", "published_at": None}])
    candidates, resolved = discover.discover("https://www.youtube.com/playlist?list=PL1", "youtube_channel")
    assert candidates == [{"item_url": "u", "title": "t", "published_at": None}]
    assert resolved is None


def test_discover_feed_direct_url_works_without_autodiscovery(monkeypatch):
    monkeypatch.setattr(discover, "discover_feed",
                        lambda url: [{"item_url": "u", "title": "t", "published_at": None}])

    def boom(url):
        raise AssertionError("find_feed_url should not be called when the direct feed works")
    monkeypatch.setattr(discover, "find_feed_url", boom)

    candidates, resolved = discover.discover("https://example.com/feed.xml", "feed")
    assert candidates == [{"item_url": "u", "title": "t", "published_at": None}]
    assert resolved is None


def test_discover_feed_falls_back_to_autodiscovery(monkeypatch):
    calls = []

    def fake_discover_feed(url):
        calls.append(url)
        if url == "https://example.com/":
            raise ValueError("not a feed")
        return [{"item_url": "u", "title": "t", "published_at": None}]
    monkeypatch.setattr(discover, "discover_feed", fake_discover_feed)
    monkeypatch.setattr(discover, "find_feed_url", lambda url: "https://example.com/feed.xml")

    candidates, resolved = discover.discover("https://example.com/", "feed")
    assert candidates == [{"item_url": "u", "title": "t", "published_at": None}]
    assert resolved == "https://example.com/feed.xml"
    assert calls == ["https://example.com/", "https://example.com/feed.xml"]


def test_discover_feed_raises_when_autodiscovery_finds_nothing(monkeypatch):
    monkeypatch.setattr(discover, "discover_feed",
                        lambda url: (_ for _ in ()).throw(ValueError("bad")))
    monkeypatch.setattr(discover, "find_feed_url", lambda url: None)

    with pytest.raises(ValueError, match="no feed found"):
        discover.discover("https://example.com/", "feed")


def test_discover_feed_parses_media_thumbnail_and_duration(monkeypatch):
    class FakeEntry(dict):
        def get(self, k, default=None):
            return dict.get(self, k, default)
    e = FakeEntry({"link": "https://y/v", "title": "T", "media_thumbnail": [{"url": "https://i/t.jpg"}],
                   "itunes_duration": "1:02:03"})
    monkeypatch.setattr(discover.feedparser, "parse", lambda url: _fake_parsed([e]))
    out = discover.discover_feed("https://y/feed")
    assert out[0]["thumb_url"] == "https://i/t.jpg"
    assert out[0]["duration_s"] == 3723


def test_discover_feed_valid_empty_feed_is_ok(monkeypatch):
    monkeypatch.setattr(discover.feedparser, "parse",
                        lambda url: types.SimpleNamespace(
                            get=lambda k, default=None: {"version": "rss20"}.get(k, default), entries=[]))
    assert discover.discover_feed("https://rss.arxiv.org/rss/cs.LG") == []


def test_discover_feed_no_entries_no_version_is_not_a_feed(monkeypatch):
    monkeypatch.setattr(discover.feedparser, "parse", lambda url: _fake_parsed([]))
    with pytest.raises(ValueError):
        discover.discover_feed("https://example.com/")


def test_discover_empty_valid_direct_feed_skips_autodiscovery(monkeypatch):
    monkeypatch.setattr(discover, "discover_feed", lambda url: [])
    monkeypatch.setattr(discover, "find_feed_url",
                        lambda url: (_ for _ in ()).throw(AssertionError("no autodiscovery")))
    assert discover.discover("https://rss.arxiv.org/rss/cs.LG", "feed") == ([], None)


@pytest.mark.parametrize("url", [
    "https://www.youtube.com/channel/UCabc_123-x/videos",
    "https://www.youtube.com/@veritasium",
    "https://youtu.be/JsBZOcqZerk",
    "https://www.youtube.com/watch?v=JsBZOcqZerk",
])
def test_youtube_urls_resolve_to_channel_rss(monkeypatch, url):
    monkeypatch.setattr(discover, "_youtube_channel_id", lambda u: "UCabc_123-x")
    assert discover.youtube_feed_url(url) == "https://www.youtube.com/feeds/videos.xml?channel_id=UCabc_123-x"


def test_youtube_feed_url_passthrough_playlist_and_nonyoutube():
    assert discover.youtube_feed_url("https://www.youtube.com/playlist?list=PL1") is None
    assert discover.youtube_feed_url("https://example.com/@x") is None
    f = "https://www.youtube.com/feeds/videos.xml?channel_id=UCx"
    assert discover.youtube_feed_url(f) == f


def test_youtube_unresolvable_raises(monkeypatch):
    monkeypatch.setattr(discover, "_youtube_channel_id", lambda u: None)
    with pytest.raises(ValueError, match="could not resolve"):
        discover.youtube_feed_url("https://www.youtube.com/@ghost")


def test_discover_youtube_handle_uses_rss_and_keeps_shorts(monkeypatch):
    monkeypatch.setattr(discover, "_youtube_channel_id", lambda u: "UCx")
    rows = [{"item_url": "https://www.youtube.com/shorts/a", "title": "s", "published_at": "p",
             "thumb_url": "t", "duration_s": None}]
    monkeypatch.setattr(discover, "discover_feed", lambda url: rows)
    cands, resolved = discover.discover("https://www.youtube.com/@x", "youtube_channel")
    assert cands == rows
    assert resolved == "https://www.youtube.com/feeds/videos.xml?channel_id=UCx"


def test_discover_unknown_source_type_raises():
    with pytest.raises(ValueError, match="unknown source_type"):
        discover.discover("https://example.com/", "carrier_pigeon")


# ---------------------------------------------------------------------------
# discover_page_watch
# ---------------------------------------------------------------------------

_PAGE_WATCH_HTML = """
<html><body>
<a href="/list/cs.LG/2026-10">New papers</a>
<a href="https://example.com/list/cs.LG/2026-09">Older papers</a>
<a href="https://other.com/ad">Sponsored</a>
<a href="#top">Back to top</a>
<a href="mailto:x@example.com">Email us</a>
<a href="javascript:void(0)">Noop</a>
<a href="/list/cs.LG/2026-10">New papers (dup)</a>
</body></html>
"""


def test_discover_page_watch_keeps_same_site_dedups_and_drops_junk(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get(_PAGE_WATCH_HTML))
    out = discover.discover_page_watch("https://example.com/list/cs.LG")
    assert out == [
        {"item_url": "https://example.com/list/cs.LG/2026-10", "title": "New papers",
         "published_at": None, "thumb_url": None, "duration_s": None},
        {"item_url": "https://example.com/list/cs.LG/2026-09", "title": "Older papers",
         "published_at": None, "thumb_url": None, "duration_s": None},
    ]


def test_discover_page_watch_www_normalizes_same_site():
    assert discover._same_site("www.example.com", "example.com")
    assert discover._same_site("example.com", "www.example.com")
    assert not discover._same_site("example.com", "other.com")


def test_discover_page_watch_second_poll_of_unchanged_page_returns_same_items(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get(_PAGE_WATCH_HTML))
    first = discover.discover_page_watch("https://example.com/list/cs.LG")
    second = discover.discover_page_watch("https://example.com/list/cs.LG")
    assert first == second   # the Java-side item_url dedup is what makes this "0 new"


def test_discover_page_watch_no_links_is_ok_not_an_error(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get", _fake_httpx_get("<html><body>no links here</body></html>"))
    assert discover.discover_page_watch("https://example.com/") == []


def test_discover_page_watch_raises_on_fetch_failure(monkeypatch):
    def boom(url, headers=None, timeout=None, follow_redirects=None):
        raise ConnectionError("dns failure")
    monkeypatch.setattr(discover.httpx, "get", boom)
    with pytest.raises(ValueError, match="could not fetch page"):
        discover.discover_page_watch("https://example.com/")


def test_discover_dispatches_page_watch(monkeypatch):
    monkeypatch.setattr(discover, "discover_page_watch",
                        lambda url, limit=50: [{"item_url": "u", "title": "t", "published_at": None,
                                                "thumb_url": None, "duration_s": None}])
    candidates, resolved = discover.discover("https://example.com/", "page_watch")
    assert candidates == [{"item_url": "u", "title": "t", "published_at": None,
                           "thumb_url": None, "duration_s": None}]
    assert resolved is None


# ---------------------------------------------------------------------------
# discover_youtube_posts
# ---------------------------------------------------------------------------

def _yt_initial_data_html(posts):
    data = {"contents": {"twoColumnBrowseResultsRenderer": {"tabs": [
        {"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            {"itemSectionRenderer": {"contents": [
                {"backstagePostThreadRenderer": {"post": {"backstagePostRenderer": p}}}
                for p in posts
            ]}},
        ]}}}},
    ]}}}
    return f'<html><head></head><body><script>var ytInitialData = {json.dumps(data)};</script></body></html>'


def _post(post_id, text):
    return {"postId": post_id, "contentText": {"runs": [{"text": text}]},
            "publishedTimeText": {"runs": [{"text": "3 days ago"}]}}


def test_discover_youtube_posts_extracts_id_title_and_body(monkeypatch):
    html_text = _yt_initial_data_html([_post("Ugkx1", "Line one\nLine two of the post")])
    monkeypatch.setattr(discover.httpx, "get",
                        lambda url, headers=None, cookies=None, timeout=None, follow_redirects=None:
                            types.SimpleNamespace(text=html_text))
    out = discover.discover_youtube_posts("https://www.youtube.com/@x/posts")
    assert out == [{
        "item_url": "https://www.youtube.com/post/Ugkx1",
        "title": "Line one",
        "published_at": None,
        "thumb_url": None,
        "duration_s": None,
        "body": "Line one\nLine two of the post",
    }]


def test_discover_youtube_posts_multiple_posts_respects_limit(monkeypatch):
    html_text = _yt_initial_data_html([_post(f"Ug{i}", f"post {i}") for i in range(5)])
    monkeypatch.setattr(discover.httpx, "get",
                        lambda url, headers=None, cookies=None, timeout=None, follow_redirects=None:
                            types.SimpleNamespace(text=html_text))
    out = discover.discover_youtube_posts("https://www.youtube.com/@x/posts", limit=2)
    assert len(out) == 2
    assert [p["item_url"] for p in out] == ["https://www.youtube.com/post/Ug0", "https://www.youtube.com/post/Ug1"]


def test_discover_youtube_posts_raises_when_no_ytinitialdata(monkeypatch):
    monkeypatch.setattr(discover.httpx, "get",
                        lambda url, headers=None, cookies=None, timeout=None, follow_redirects=None:
                            types.SimpleNamespace(text="<html>blocked / consent wall</html>"))
    with pytest.raises(ValueError, match="no ytInitialData found"):
        discover.discover_youtube_posts("https://www.youtube.com/@x/posts")


def test_discover_youtube_posts_raises_on_fetch_failure(monkeypatch):
    def boom(url, headers=None, cookies=None, timeout=None, follow_redirects=None):
        raise ConnectionError("timeout")
    monkeypatch.setattr(discover.httpx, "get", boom)
    with pytest.raises(ValueError, match="could not fetch"):
        discover.discover_youtube_posts("https://www.youtube.com/@x/posts")


def test_discover_dispatches_youtube_posts(monkeypatch):
    monkeypatch.setattr(discover, "discover_youtube_posts",
                        lambda url, limit=20: [{"item_url": "u", "title": "t", "published_at": None,
                                                "thumb_url": None, "duration_s": None, "body": "b"}])
    candidates, resolved = discover.discover("https://www.youtube.com/@x/posts", "youtube_posts")
    assert candidates == [{"item_url": "u", "title": "t", "published_at": None,
                           "thumb_url": None, "duration_s": None, "body": "b"}]
    assert resolved is None


# ---------------------------------------------------------------------------
# POST /subscriptions/discover
# ---------------------------------------------------------------------------

def test_subscriptions_discover_endpoint_happy_path(monkeypatch, client):
    monkeypatch.setattr(discover, "discover",
                        lambda source_url, source_type: (
                            [{"item_url": "u", "title": "t", "published_at": None}],
                            "https://example.com/feed.xml",
                        ))
    resp = client.post("/subscriptions/discover",
                       json={"source_url": "https://example.com/", "source_type": "feed"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["candidates"] == [{"item_url": "u", "title": "t", "published_at": None}]
    assert body["resolvedFeedUrl"] == "https://example.com/feed.xml"


def test_subscriptions_discover_endpoint_422_on_value_error(monkeypatch, client):
    def boom(source_url, source_type):
        raise ValueError("unknown source_type: bogus")
    monkeypatch.setattr(discover, "discover", boom)
    resp = client.post("/subscriptions/discover",
                       json={"source_url": "https://example.com/", "source_type": "bogus"})
    assert resp.status_code == 422
