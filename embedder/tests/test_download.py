"""Download core tests — yt-dlp salvaged from the former VideoManager app.

yt_dlp itself is stubbed (tests/conftest.py) and monkeypatched per-test, so the
suite runs offline with no real network or yt-dlp install.
"""
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import main as embedder_main
from download import downloader, jobs as download_jobs


# ---------------------------------------------------------------------------
# build_ydl_opts / parse_progress (pure)
# ---------------------------------------------------------------------------

def test_build_ydl_opts_without_hook_has_no_progress_hooks():
    opts = downloader.build_ydl_opts(None)
    assert "progress_hooks" not in opts
    assert opts["merge_output_format"] == "mp4"
    assert "outtmpl" in opts


def test_build_ydl_opts_with_hook_registers_it():
    hook = lambda d: None
    opts = downloader.build_ydl_opts(hook)
    assert opts["progress_hooks"] == [hook]


def test_parse_progress_normalises_fields():
    out = downloader.parse_progress({
        "_percent_str": " 42.5%", "_speed_str": " 1.2MiB/s ",
        "_eta_str": " 00:30 ", "filename": "vid.mp4",
    })
    assert out == {"progress": 42.5, "speed": "1.2MiB/s",
                   "eta": "00:30", "filename": "vid.mp4"}


def test_parse_progress_defaults_when_missing():
    out = downloader.parse_progress({})
    assert out["progress"] == 0.0
    assert out["speed"] == "N/A"
    assert out["eta"] == "N/A"
    assert out["filename"] == ""


def test_parse_progress_handles_garbage_percent():
    out = downloader.parse_progress({"_percent_str": "??%"})
    assert out["progress"] == 0.0


# ---------------------------------------------------------------------------
# download_sync
# ---------------------------------------------------------------------------

def _fake_ydl(info, captured=None):
    class FakeYDL:
        def __init__(self, opts):
            if captured is not None:
                captured["opts"] = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download=False):
            return info

        def prepare_filename(self, i):
            return i["_filename"]
    return FakeYDL


def test_download_sync_returns_prepared_filename(monkeypatch, tmp_path):
    monkeypatch.setattr(downloader, "DOWNLOAD_DIR", str(tmp_path))
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL",
                        _fake_ydl({"_filename": str(tmp_path / "clip.mp4")}))
    out = downloader.download_sync("https://youtu.be/x")
    assert out.endswith("clip.mp4")
    assert tmp_path.exists()  # download dir created


def test_download_sync_reports_last_playlist_entry(monkeypatch, tmp_path):
    monkeypatch.setattr(downloader, "DOWNLOAD_DIR", str(tmp_path))
    info = {"entries": [
        {"_filename": str(tmp_path / "a.mp4")},
        {"_filename": str(tmp_path / "b.mp4")},
    ]}
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", _fake_ydl(info))
    out = downloader.download_sync("https://list")
    assert out.endswith("b.mp4")


# ---------------------------------------------------------------------------
# list_playlist_entries
# ---------------------------------------------------------------------------

def test_list_playlist_entries_returns_url_and_title(monkeypatch):
    info = {"entries": [
        {"id": "vid1", "url": "vid1", "title": "Part 1"},
        {"id": "vid2", "url": "https://www.youtube.com/watch?v=vid2", "title": "Part 2"},
    ]}
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", _fake_ydl(info))
    out = downloader.list_playlist_entries("https://www.youtube.com/playlist?list=PL1")
    assert out == [
        {"url": "https://www.youtube.com/watch?v=vid1", "title": "Part 1"},
        {"url": "https://www.youtube.com/watch?v=vid2", "title": "Part 2"},
    ]


def test_list_playlist_entries_skips_download(monkeypatch):
    captured = {}
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL",
                        _fake_ydl({"entries": [{"id": "v", "url": "v", "title": "T"}]}, captured))
    downloader.list_playlist_entries("https://www.youtube.com/playlist?list=PL1")
    assert captured["opts"]["skip_download"] is True
    assert captured["opts"]["extract_flat"] == "in_playlist"


def test_list_playlist_entries_raises_when_not_a_playlist(monkeypatch):
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", _fake_ydl({"title": "single video"}))
    with pytest.raises(ValueError, match="not a playlist"):
        downloader.list_playlist_entries("https://youtu.be/x")


# ---------------------------------------------------------------------------
# /playlist/expand endpoint
# ---------------------------------------------------------------------------

def test_playlist_expand_endpoint_success(monkeypatch, client):
    monkeypatch.setattr(downloader, "list_playlist_entries",
                        lambda url: [{"url": "https://youtu.be/a", "title": "A"}])
    resp = client.post("/playlist/expand", json={"url": "https://www.youtube.com/playlist?list=PL1"})
    assert resp.status_code == 200
    assert resp.json()["entries"] == [{"url": "https://youtu.be/a", "title": "A"}]


def test_playlist_expand_endpoint_422_when_not_a_playlist(monkeypatch, client):
    def boom(url):
        raise ValueError("not a playlist (no entries)")
    monkeypatch.setattr(downloader, "list_playlist_entries", boom)
    resp = client.post("/playlist/expand", json={"url": "https://youtu.be/x"})
    assert resp.status_code == 422


def test_playlist_expand_endpoint_422_when_empty_url(client):
    assert client.post("/playlist/expand", json={"url": "  "}).status_code == 422


# ---------------------------------------------------------------------------
# fetch_subs
# ---------------------------------------------------------------------------

def _fake_ydl_writes_vtt(vtt_body, title="Lecture 1", duration=123):
    class FakeYDL:
        def __init__(self, opts):
            self._tmpl = opts["outtmpl"]  # f"{tmp}/subs.%(ext)s"

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download=False):
            tmp_dir = Path(self._tmpl).parent
            (tmp_dir / "subs.en.vtt").write_text(vtt_body, encoding="utf-8")
            return {"title": title, "duration": duration}
    return FakeYDL


def test_fetch_subs_returns_vtt_title_duration(monkeypatch):
    body = "WEBVTT\n\n00:00.000 --> 00:02.000\nhello\n"
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", _fake_ydl_writes_vtt(body))
    out = downloader.fetch_subs("https://youtu.be/x")
    assert out["vtt"] == body
    assert out["title"] == "Lecture 1"
    assert out["duration_s"] == 123.0


def test_fetch_subs_raises_when_no_captions(monkeypatch):
    class NoVtt:
        def __init__(self, opts): pass
        def __enter__(self): return self
        def __exit__(self, *exc): return False
        def extract_info(self, url, download=False): return {"title": "x"}
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", NoVtt)
    with pytest.raises(RuntimeError, match="no en captions"):
        downloader.fetch_subs("https://youtu.be/x")


# ---------------------------------------------------------------------------
# jobs — async lifecycle
# ---------------------------------------------------------------------------

def _wait_for(job_id, terminal=("done", "error"), timeout=3.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = download_jobs.get(job_id)
        if job and job["status"] in terminal:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} did not finish in {timeout}s")


def test_job_succeeds_and_reports_progress(monkeypatch):
    def fake_download(url, hook):
        hook({"status": "downloading", "_percent_str": "50.0%",
              "_speed_str": "1MiB/s", "_eta_str": "5", "filename": "x.mp4"})
        hook({"status": "finished", "filename": "x.mp4"})
        return "/downloads/x.mp4"
    monkeypatch.setattr(downloader, "download_sync", fake_download)

    submitted = download_jobs.submit("https://youtu.be/x")
    assert submitted["id"]  # status may already be 'done' (fast fake) — don't race on it
    job = _wait_for(submitted["id"])
    assert job["status"] == "done"
    assert job["progress"] == 100.0
    assert job["filename"] == "/downloads/x.mp4"


def test_job_records_error(monkeypatch):
    def boom(url, hook):
        raise RuntimeError("private video")
    monkeypatch.setattr(downloader, "download_sync", boom)

    submitted = download_jobs.submit("https://youtu.be/x")
    job = _wait_for(submitted["id"])
    assert job["status"] == "error"
    assert "private video" in job["error"]


def test_get_unknown_job_returns_none():
    assert download_jobs.get("nope-not-real") is None


# ---------------------------------------------------------------------------
# HTTP endpoints
# ---------------------------------------------------------------------------

@pytest.fixture
def client():
    return TestClient(embedder_main.app, raise_server_exceptions=True)


def test_post_download_starts_job_and_status_is_queryable(monkeypatch, client):
    def fake_download(url, hook):
        hook({"status": "finished", "filename": "done.mp4"})
        return "/downloads/done.mp4"
    monkeypatch.setattr(downloader, "download_sync", fake_download)

    resp = client.post("/download", json={"url": "https://youtu.be/x"})
    assert resp.status_code == 200
    job_id = resp.json()["id"]

    job = _wait_for(job_id)
    status = client.get(f"/download/{job_id}")
    assert status.status_code == 200
    assert status.json()["status"] == "done"


def test_post_download_empty_url_returns_422(client):
    assert client.post("/download", json={"url": "   "}).status_code == 422


def test_get_unknown_download_returns_404(client):
    assert client.get("/download/does-not-exist").status_code == 404


def test_download_list_endpoint(monkeypatch, client):
    monkeypatch.setattr(downloader, "download_sync",
                        lambda url, hook: "/downloads/y.mp4")
    client.post("/download", json={"url": "https://youtu.be/y"})
    resp = client.get("/download")
    assert resp.status_code == 200
    assert isinstance(resp.json()["jobs"], list)


def test_subs_endpoint_success(monkeypatch, client):
    body = "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n"
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", _fake_ydl_writes_vtt(body))
    resp = client.post("/subs", json={"url": "https://youtu.be/x"})
    assert resp.status_code == 200
    assert resp.json()["vtt"] == body


def test_subs_endpoint_422_when_unavailable(monkeypatch, client):
    class NoVtt:
        def __init__(self, opts): pass
        def __enter__(self): return self
        def __exit__(self, *exc): return False
        def extract_info(self, url, download=False): return {}
    monkeypatch.setattr(downloader.yt_dlp, "YoutubeDL", NoVtt)
    assert client.post("/subs", json={"url": "https://youtu.be/x"}).status_code == 422
