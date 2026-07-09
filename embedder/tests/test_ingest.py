"""Ingest stage 1 tests — routing, VTT parsing, job lifecycle, API endpoints.

Whisper/ffmpeg are never invoked here: extractor is stubbed at the jobs layer.
"""
import json
import time

import pytest

from ingest import jobs as ingest_jobs
from ingest import router as ingest_router
from ingest.extract_av import parse_vtt


# ── router ───────────────────────────────────────────────────────────────

@pytest.mark.parametrize("ref,expected", [
    ("resources/videos/lecture.mp4", "av"),
    ("resources/audio/podcast.mp3", "av"),
    ("folder/slides.PDF", "pdf"),
    ("resources/images/shot.png", "image"),
    ("https://youtube.com/watch?v=abc123", "youtube"),
    ("https://youtu.be/abc123", "youtube"),
    ("https://example.com/article", "web"),
    ("https://example.com/files/talk.mp4", "av"),
])
def test_route_table(ref, expected):
    assert ingest_router.route(ref) == expected


def test_route_rejects_unknown_extension():
    with pytest.raises(ValueError, match="no route"):
        ingest_router.route("notes/weird.xyz")


def test_is_audio():
    assert ingest_router.is_audio("a/b.mp3")
    assert not ingest_router.is_audio("a/b.mp4")


# ── VTT parsing ──────────────────────────────────────────────────────────

VTT_BASIC = """WEBVTT
Kind: captions
Language: en

00:00:01.000 --> 00:00:03.500
hello world

00:00:03.500 --> 00:00:06.000
second <c.color>cue</c> here
"""


def test_parse_vtt_basic():
    segs = parse_vtt(VTT_BASIC)
    assert len(segs) == 2
    assert segs[0] == {"start": 1.0, "end": 3.5, "text": "hello world"}
    assert segs[1]["text"] == "second cue here"   # inline tags stripped


VTT_ROLLING = """WEBVTT

00:00:00.000 --> 00:00:02.000
the quick brown fox

00:00:02.000 --> 00:00:04.000
the quick brown fox

00:00:04.000 --> 00:00:06.000
the quick brown fox jumps over
"""


def test_parse_vtt_dedupes_rolling_window():
    segs = parse_vtt(VTT_ROLLING)
    assert len(segs) == 2
    # exact repeat merged into the first cue's span
    assert segs[0]["text"] == "the quick brown fox"
    assert segs[0]["end"] == 4.0
    # prefix repeat trimmed to only the new words
    assert segs[1]["text"] == "jumps over"


def test_parse_vtt_hours_timestamp():
    segs = parse_vtt("WEBVTT\n\n01:02:03.250 --> 01:02:05.000\ndeep into the lecture\n")
    assert segs[0]["start"] == 3723.25


# ── job lifecycle (extractor stubbed) ────────────────────────────────────

@pytest.fixture
def stub_extract(monkeypatch, tmp_path):
    monkeypatch.setattr(ingest_jobs, "BUNDLE_DIR", tmp_path / "bundles")
    from ingest import extract_av

    def fake_extract(ref, resolved, force_whisper=False):
        return {"source": {"type": "audio", "ref": ref, "title": "t",
                           "duration_s": 9.0, "chapters": []},
                "segments": [{"loc": {"t_start": 0.0, "t_end": 9.0}, "text": "hi"}],
                "media": []}

    monkeypatch.setattr(extract_av, "extract", fake_extract)
    return tmp_path


def _wait_done(job_id, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = ingest_jobs.get(job_id)
        if job["status"] in ("DONE", "FAILED"):
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} did not finish: {ingest_jobs.get(job_id)}")


def test_job_runs_to_done_and_persists_bundle(stub_extract, tmp_path):
    job = ingest_jobs.submit("resources/audio/x.mp3", tmp_path / "x.mp3",
                             extract_only=True)
    assert job["status"] in ("QUEUED", "RUNNING")
    done = _wait_done(job["id"])
    assert done["status"] == "DONE"
    assert done["segments"] == 1
    bundle = json.loads((tmp_path / "bundles" / f"{job['id']}.json")
                        .read_text(encoding="utf-8"))
    assert bundle["source"]["ref"] == "resources/audio/x.mp3"


def test_job_failure_is_captured_not_fatal(stub_extract, monkeypatch):
    from ingest import extract_av

    def boom(ref, resolved, force_whisper=False):
        raise RuntimeError("ffmpeg exploded")

    monkeypatch.setattr(extract_av, "boom_marker", True, raising=False)
    monkeypatch.setattr(extract_av, "extract", boom)
    job = ingest_jobs.submit("resources/audio/bad.mp3", None, extract_only=True)
    done = _wait_done(job["id"])
    assert done["status"] == "FAILED"
    assert "ffmpeg exploded" in done["error"]
    # worker survives — next job still runs
    monkeypatch.setattr(extract_av, "extract",
                        lambda ref, resolved, force_whisper=False: {
                            "source": {"type": "audio", "ref": ref, "title": "t",
                                       "duration_s": 1.0, "chapters": []},
                            "segments": [], "media": []})
    job2 = ingest_jobs.submit("resources/audio/ok.mp3", None, extract_only=True)
    assert _wait_done(job2["id"])["status"] == "DONE"


# ── synthesis durability: DEFER on provider exhaustion, resume from bundle ──

def _wait_status(job_id, statuses, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = ingest_jobs.get(job_id)
        if job["status"] in statuses:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} not in {statuses}: {ingest_jobs.get(job_id)}")


def test_synthesis_503_defers_not_fails(stub_extract, monkeypatch, tmp_path):
    """All LLM providers cooling (wrapper 503) → job DEFERRED, bundle kept on disk."""
    from ingest.synthesize import SynthesisError

    def cooling(job, bundle):
        raise SynthesisError("wrapper /complete 503: all providers cooling", status=503)

    monkeypatch.setattr(ingest_jobs, "_synthesize_and_publish", cooling)
    job = ingest_jobs.submit("resources/audio/x.mp3", tmp_path / "x.mp3")  # standalone, full run
    done = _wait_status(job["id"], ("DEFERRED", "FAILED", "DONE"))
    assert done["status"] == "DEFERRED"
    assert (tmp_path / "bundles" / f"{job['id']}.json").is_file()   # bundle retained for resume


def test_synthesis_non_503_still_fails(stub_extract, monkeypatch, tmp_path):
    from ingest.synthesize import SynthesisError

    def broken(job, bundle):
        raise SynthesisError("wrapper /complete 500: boom", status=500)

    monkeypatch.setattr(ingest_jobs, "_synthesize_and_publish", broken)
    job = ingest_jobs.submit("resources/audio/x.mp3", tmp_path / "x.mp3")
    done = _wait_status(job["id"], ("DEFERRED", "FAILED", "DONE"))
    assert done["status"] == "FAILED"


def test_resume_from_bundle_skips_extraction(stub_extract, monkeypatch, tmp_path):
    """Resume synthesizes from a saved bundle WITHOUT re-extracting (the durable retry)."""
    from ingest import extract_av

    # a bundle already sitting on disk (as a prior DEFERRED job left it)
    bundles = tmp_path / "bundles"
    bundles.mkdir(parents=True, exist_ok=True)
    bundle_path = bundles / "saved.json"
    bundle_path.write_text(json.dumps({
        "source": {"type": "video", "ref": "https://y/x", "title": "t",
                   "duration_s": 5.0, "chapters": []},
        "segments": [{"loc": {"t_start": 0, "t_end": 5}, "text": "hi"}],
        "media": []}), encoding="utf-8")

    extract_called = {"n": 0}
    monkeypatch.setattr(extract_av, "extract",
                        lambda *a, **k: extract_called.__setitem__("n", extract_called["n"] + 1))
    synth = {"n": 0}
    monkeypatch.setattr(ingest_jobs, "_synthesize_and_publish",
                        lambda job, bundle: synth.__setitem__("n", synth["n"] + 1))

    job = ingest_jobs.submit("https://y/x", None, resume_bundle=str(bundle_path),
                             capture_id="cap-1")
    done = _wait_status(job["id"], ("DONE", "FAILED", "DEFERRED"))
    assert done["status"] == "DONE"
    assert extract_called["n"] == 0   # NO re-extraction
    assert synth["n"] == 1            # synthesis ran once


def test_private_fields_not_exposed(stub_extract):
    # extract_only: this test only inspects the public view's keys, but a full
    # job would wedge the single worker on publish's httpx call to an unreachable
    # BACKEND_URL (60s), starving the next test's poll. See conftest _reset_ingest_jobs.
    job = ingest_jobs.submit("resources/audio/x.mp3", None, extract_only=True)
    assert not any(k.startswith("_") for k in job)
    assert not any(k.startswith("_") for k in ingest_jobs.get(job["id"]))


# ── API endpoints ────────────────────────────────────────────────────────

@pytest.fixture
def client(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient
    import main as embedder_main
    import mcp_server

    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    monkeypatch.setattr(ingest_jobs, "BUNDLE_DIR", tmp_path / "bundles")
    return TestClient(embedder_main.app)


def test_ingest_endpoint_422_on_unroutable(client):
    res = client.post("/ingest", json={"ref": "weird.xyz"})
    assert res.status_code == 422


def test_ingest_endpoint_404_on_missing_local_file(client):
    res = client.post("/ingest", json={"ref": "resources/videos/ghost.mp4"})
    assert res.status_code == 404


def test_resume_endpoint_rejects_bundle_outside_dir(client, tmp_path):
    # path traversal / arbitrary file → 404 (only bundles under BUNDLE_DIR resume)
    res = client.post("/ingest/resume", json={"bundle_path": "/etc/passwd"})
    assert res.status_code == 404


def test_resume_endpoint_accepts_saved_bundle(client, tmp_path, monkeypatch):
    bundles = tmp_path / "bundles"
    bundles.mkdir(parents=True, exist_ok=True)
    bp = bundles / "b1.json"
    bp.write_text(json.dumps({
        "source": {"type": "video", "ref": "https://y/x", "title": "t",
                   "duration_s": 3.0, "chapters": []},
        "segments": [{"loc": {"t_start": 0, "t_end": 3}, "text": "hi"}],
        "media": []}), encoding="utf-8")
    monkeypatch.setattr(ingest_jobs, "_synthesize_and_publish", lambda job, bundle: None)

    res = client.post("/ingest/resume",
                      json={"bundle_path": str(bp), "capture_id": "cap-9", "ref": "https://y/x"})
    assert res.status_code == 200
    assert res.json()["status"] in ("QUEUED", "RUNNING", "DONE", "DEFERRED")


def test_ingest_endpoint_accepts_existing_file(client, tmp_path, monkeypatch):
    from ingest import extract_av
    monkeypatch.setattr(extract_av, "extract",
                        lambda ref, resolved, force_whisper=False: {
                            "source": {"type": "video", "ref": ref, "title": "t",
                                       "duration_s": 2.0, "chapters": []},
                            "segments": [], "media": []})
    f = tmp_path / "resources" / "videos" / "clip.mp4"
    f.parent.mkdir(parents=True)
    f.write_bytes(b"fake")

    res = client.post("/ingest", json={"ref": "resources/videos/clip.mp4",
                                       "extract_only": True})
    assert res.status_code == 200
    job_id = res.json()["id"]
    deadline = time.time() + 5
    while time.time() < deadline:
        body = client.get(f"/ingest/{job_id}").json()
        if body["status"] in ("DONE", "FAILED"):
            break
        time.sleep(0.02)
    assert body["status"] == "DONE"
    assert any(j["id"] == job_id for j in client.get("/ingest").json()["jobs"])


def test_ingest_status_404_unknown_job(client):
    assert client.get("/ingest/nope").status_code == 404
