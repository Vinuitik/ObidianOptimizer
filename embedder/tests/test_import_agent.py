"""Excel/CSV import agent tests — JSON schema/retry discipline over a single mapping
object. All LLM calls stubbed (`import_agent._complete`), same convention as
test_minicourse.py."""
import json

import pytest

from tracks import import_agent


CSV = "Book,Chapter,Done\nDeep Work,Ch1,yes\nDeep Work,Ch2,no\nAtomic Habits,Intro,no\n"


def valid_payload():
    return {"tracks": [{"title": "Deep Work", "type": "book"},
                       {"title": "Atomic Habits", "type": "book"}],
            "items": [{"trackIndex": 0, "title": "Ch1", "status": "done"},
                      {"trackIndex": 0, "title": "Ch2", "status": "pending"},
                      {"trackIndex": 1, "title": "Intro", "status": "pending"}]}


def test_valid_csv_first_attempt(monkeypatch):
    monkeypatch.setattr(import_agent, "_complete", lambda p: json.dumps(valid_payload()))
    result = import_agent.map_csv_to_tracks(CSV)
    assert result["tracks"] == valid_payload()["tracks"]
    assert [i["title"] for i in result["items"]] == ["Ch1", "Ch2", "Intro"]


def test_malformed_json_then_valid_retries(monkeypatch):
    replies = iter(["not json at all", json.dumps(valid_payload())])
    monkeypatch.setattr(import_agent, "_complete", lambda p: next(replies))
    result = import_agent.map_csv_to_tracks(CSV)
    assert result["tracks"][0]["title"] == "Deep Work"


def test_out_of_range_track_index_triggers_retry(monkeypatch):
    bad = json.loads(json.dumps(valid_payload()))
    bad["items"][0]["trackIndex"] = 99
    good = valid_payload()
    replies = iter([json.dumps(bad), json.dumps(good)])
    monkeypatch.setattr(import_agent, "_complete", lambda p: next(replies))
    result = import_agent.map_csv_to_tracks(CSV)
    assert result["items"][0]["trackIndex"] == 0


def test_persistently_invalid_raises_value_error(monkeypatch):
    monkeypatch.setattr(import_agent, "_complete", lambda p: "garbage")
    with pytest.raises(ValueError, match="map_csv_to_tracks failed"):
        import_agent.map_csv_to_tracks(CSV)


def test_empty_csv_raises_without_calling_llm(monkeypatch):
    calls = []
    monkeypatch.setattr(import_agent, "_complete", lambda p: calls.append(p) or "{}")
    with pytest.raises(ValueError, match="empty"):
        import_agent.map_csv_to_tracks("   \n  ")
    assert calls == []


def test_items_bucketed_by_track_index_preserve_row_order(monkeypatch):
    payload = {
        "tracks": [{"title": "Track A", "type": "book"}, {"title": "Track B", "type": "course"}],
        "items": [
            {"trackIndex": 1, "title": "B item 1", "status": "pending"},
            {"trackIndex": 0, "title": "A item 1", "status": "done"},
            {"trackIndex": 1, "title": "B item 2", "status": "pending"},
        ],
    }
    monkeypatch.setattr(import_agent, "_complete", lambda p: json.dumps(payload))
    result = import_agent.map_csv_to_tracks(CSV)

    a_items = [i["title"] for i in result["items"] if i["trackIndex"] == 0]
    b_items = [i["title"] for i in result["items"] if i["trackIndex"] == 1]
    assert a_items == ["A item 1"]
    assert b_items == ["B item 1", "B item 2"]
