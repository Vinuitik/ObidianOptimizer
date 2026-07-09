"""extract_av.extract() dispatch — the captions→whisper fallback safety net.

A YouTube video with no captions must NOT fail: it falls through to the whisper
download+transcribe path. A video WITH captions never triggers the (slow) whisper path.
"""
from ingest import extract_av


def _seg(text="hello world"):
    return [{"start": 0.0, "end": 1.0, "text": text}]


def test_missing_captions_falls_back_to_whisper(monkeypatch):
    monkeypatch.setattr(extract_av.ingest_router, "route", lambda ref: "youtube")

    def no_captions(url):
        raise RuntimeError("no usable captions — re-run with force_whisper")

    called = {"whisper": False}

    def fake_whisper(url):
        called["whisper"] = True
        return _seg("from whisper"), "Lecture", 12.0

    monkeypatch.setattr(extract_av, "_youtube_captions", no_captions)
    monkeypatch.setattr(extract_av, "_youtube_whisper", fake_whisper)

    bundle = extract_av.extract("https://www.youtube.com/watch?v=abc", None)

    assert called["whisper"] is True
    assert bundle["segments"][0]["text"] == "from whisper"
    assert bundle["source"]["duration_s"] == 12.0


def test_captions_present_skips_whisper(monkeypatch):
    monkeypatch.setattr(extract_av.ingest_router, "route", lambda ref: "youtube")

    def good_captions(url):
        return _seg("from captions"), "Lecture", 30.0

    def must_not_run(url):
        raise AssertionError("whisper must not run when captions exist")

    monkeypatch.setattr(extract_av, "_youtube_captions", good_captions)
    monkeypatch.setattr(extract_av, "_youtube_whisper", must_not_run)

    bundle = extract_av.extract("https://www.youtube.com/watch?v=abc", None)

    assert bundle["segments"][0]["text"] == "from captions"


def test_force_whisper_bypasses_captions(monkeypatch):
    monkeypatch.setattr(extract_av.ingest_router, "route", lambda ref: "youtube")

    def must_not_run(url):
        raise AssertionError("captions must not run under force_whisper")

    monkeypatch.setattr(extract_av, "_youtube_captions", must_not_run)
    monkeypatch.setattr(extract_av, "_youtube_whisper",
                        lambda url: (_seg("forced"), "Lecture", 5.0))

    bundle = extract_av.extract("https://www.youtube.com/watch?v=abc", None,
                                force_whisper=True)

    assert bundle["segments"][0]["text"] == "forced"
