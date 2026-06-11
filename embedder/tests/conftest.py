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

# onnxruntime
ort_mod = _stub_module("onnxruntime")
ort_mod.get_available_providers = lambda: ["CPUExecutionProvider"]

# optimum
_stub_module("optimum")
ort_sub = _stub_module("optimum.onnxruntime")

class _FakeORTModel:
    @classmethod
    def from_pretrained(cls, *args, **kwargs):
        return cls()

ort_sub.ORTModelForFeatureExtraction = _FakeORTModel

# transformers
trans = _stub_module("transformers")

class _FakeTokenizer:
    @classmethod
    def from_pretrained(cls, *args, **kwargs):
        return cls()

trans.AutoTokenizer = _FakeTokenizer

# torch (imported transitively)
_stub_module("torch")
