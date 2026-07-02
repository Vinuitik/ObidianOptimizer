"""
Stub heavy ML imports so the test suite runs without the Docker environment.
Must be evaluated before any test module imports `main`.
"""
import sys
import types

def _stub_module(name):
    mod = types.ModuleType(name)
    sys.modules[name] = mod
    return mod

# onnxruntime — inference is pure onnxruntime now (no optimum/torch). Tests inject
# a fake session into model_runtime.state, so InferenceSession is never built here.
ort_mod = _stub_module("onnxruntime")
ort_mod.get_available_providers = lambda: ["CPUExecutionProvider"]
# model_runtime references these at import time (session opts are built lazily,
# but the _ORT_OPT level map is module-level).
ort_mod.GraphOptimizationLevel = types.SimpleNamespace(
    ORT_ENABLE_ALL="all", ORT_ENABLE_EXTENDED="extended",
    ORT_ENABLE_BASIC="basic", ORT_DISABLE_ALL="disabled")
ort_mod.SessionOptions = types.SimpleNamespace
ort_mod.InferenceSession = None  # tests inject fake sessions via state

# transformers
trans = _stub_module("transformers")

class _FakeTokenizer:
    @classmethod
    def from_pretrained(cls, *args, **kwargs):
        return cls()

trans.AutoTokenizer = _FakeTokenizer

# yt_dlp — the download/captions core imports it at module load. Tests monkeypatch
# download.downloader.yt_dlp.YoutubeDL with a fake; this stub just lets the import
# succeed outside Docker. The default raises so a forgotten patch fails loudly.
ytdlp_mod = _stub_module("yt_dlp")

class _StubYoutubeDL:
    def __init__(self, *args, **kwargs):
        self.opts = args[0] if args else {}

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, *args, **kwargs):
        raise RuntimeError("yt_dlp is stubbed — monkeypatch YoutubeDL in the test")

    def prepare_filename(self, info):
        return info.get("_filename", "stub.mp4")

ytdlp_mod.YoutubeDL = _StubYoutubeDL
