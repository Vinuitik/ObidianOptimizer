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


def test_sanitize_unit_name():
    assert publish.sanitize_unit_name('Karpathy: Tokenizers/lecture?') == "Karpathy Tokenizers lecture"
    assert publish.sanitize_unit_name("  _..  ") == ""
    assert len(publish.sanitize_unit_name("x" * 200)) == 80


def test_unit_folder_name_collision_and_reuse(tmp_path):
    inbox = tmp_path / "_inbox"
    (inbox / "My Talk").mkdir(parents=True)
    (inbox / "My Talk" / "a.md").write_text("---\ncapture-id: aaa\n---\nx")
    assert publish.unit_folder_name("My Talk", "bbb", tmp_path) == "My Talk 2"
    assert publish.unit_folder_name("My Talk", "aaa", tmp_path) == "My Talk"      # ours -> reuse
    (inbox / "My Talk 2").mkdir()
    assert publish.unit_folder_name("My Talk", "bbb", tmp_path) == "My Talk 2"    # empty -> reuse
    assert publish.unit_folder_name("", "bbb", tmp_path) == "bbb"                 # no title -> id
    assert publish.inbox_folder("My Talk", "Ch 1") == "_inbox/My Talk/Ch-1"


def test_mcp_capture_id_stable():
    assert publish.mcp_capture_id(1, "Mobile PWA Notes") == publish.mcp_capture_id(1, " mobile pwa notes ")
    assert publish.mcp_capture_id(1, "a") != publish.mcp_capture_id(2, "a")
    assert publish.mcp_capture_id(1, "a").startswith("mcp-")
