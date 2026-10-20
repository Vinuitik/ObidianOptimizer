"""Tests for ingest.publish write-through helpers touched by the capture_id
track-item grouping change (item-grouping step 3b)."""
from ingest import publish


class _OKResp:
    status_code = 200
    text = ""

    def json(self):
        return {}


def test_add_track_item_removed():
    assert not hasattr(publish, "add_track_item")   # I1: tracks never ingest raw material
