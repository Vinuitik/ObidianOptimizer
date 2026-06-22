"""GPU slot arbiter — occupant transitions, eviction, and the non-blocking
embedder fallback. Pure stdlib (no ML deps), so it runs anywhere."""
import importlib

import pytest


@pytest.fixture
def slot():
    """Fresh module state per test (module-level globals)."""
    import gpu_slot
    importlib.reload(gpu_slot)
    gpu_slot.set_gpu_available(True)
    return gpu_slot


def test_embedder_uses_gpu_when_free(slot):
    with slot.embedder_session(lambda: "GPU_SESS") as sess:
        assert sess == "GPU_SESS"
        assert slot.occupant() == "embedder"


def test_embedder_falls_back_to_cpu_when_ingest_holds_slot(slot):
    evicted = []
    slot.set_evictor("embedder", lambda: evicted.append("embedder"))
    # whisper claims the slot and stays occupant after the `with` (lazy)
    with slot.exclusive("whisper"):
        pass
    assert slot.occupant() == "whisper"
    # embedder must NOT get the GPU now, and must NOT evict whisper
    with slot.embedder_session(lambda: "GPU_SESS") as sess:
        assert sess is None
    assert slot.occupant() == "whisper"
    assert evicted == []


def test_ingest_evicts_the_embedder(slot):
    evicted = []
    slot.set_evictor("embedder", lambda: evicted.append("embedder"))
    with slot.embedder_session(lambda: "GPU_SESS"):
        pass
    assert slot.occupant() == "embedder"
    with slot.exclusive("whisper"):           # must evict the embedder
        pass
    assert slot.occupant() == "whisper"
    assert evicted == ["embedder"]


def test_release_ingest_frees_only_ingest(slot):
    slot.set_evictor("whisper", lambda: None)
    with slot.exclusive("whisper"):
        pass
    slot.release_ingest()
    assert slot.occupant() is None
    # release_ingest is a no-op when the embedder holds the slot
    with slot.embedder_session(lambda: "S"):
        pass
    slot.release_ingest()
    assert slot.occupant() == "embedder"


def test_clip_evicts_whisper(slot):
    order = []
    slot.set_evictor("whisper", lambda: order.append("evict-whisper"))
    with slot.exclusive("whisper"):
        pass
    with slot.exclusive("clip"):
        pass
    assert order == ["evict-whisper"]
    assert slot.occupant() == "clip"


def test_no_gpu_means_embedder_gets_none(slot):
    slot.set_gpu_available(False)
    with slot.embedder_session(lambda: "GPU_SESS") as sess:
        assert sess is None


def test_disabled_via_env(monkeypatch, slot):
    monkeypatch.setattr(slot, "ENABLED", False)
    with slot.embedder_session(lambda: "GPU_SESS") as sess:
        assert sess is None


def test_failed_gpu_load_falls_back_and_releases(slot):
    def boom():
        raise RuntimeError("CUDA init failed")
    with slot.embedder_session(boom) as sess:
        assert sess is None
    # lock must have been released — a subsequent exclusive() must not deadlock
    with slot.exclusive("whisper"):
        pass
    assert slot.occupant() == "whisper"
