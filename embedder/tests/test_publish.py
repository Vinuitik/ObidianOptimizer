"""Tests for ingest.publish write-through helpers touched by the capture_id
track-item grouping change (item-grouping step 3b)."""
from ingest import publish


class _OKResp:
    status_code = 200
    text = ""

    def json(self):
        return {}


def test_add_track_item_includes_capture_id_in_body(monkeypatch):
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured["json"] = json
        return _OKResp()

    monkeypatch.setattr(publish.httpx, "post", fake_post)
    monkeypatch.setattr(publish, "INTERNAL_TOKEN", "tok")

    publish.add_track_item("trk1", "Note Title", "path/to/note.md", capture_id="cap1")

    assert captured["json"] == {"title": "Note Title", "notePath": "path/to/note.md",
                                "captureId": "cap1"}


def test_add_track_item_omitted_capture_id_sends_null(monkeypatch):
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured["json"] = json
        return _OKResp()

    monkeypatch.setattr(publish.httpx, "post", fake_post)
    monkeypatch.setattr(publish, "INTERNAL_TOKEN", "tok")

    publish.add_track_item("trk1", "Note Title", "path/to/note.md")

    assert captured["json"]["captureId"] is None
