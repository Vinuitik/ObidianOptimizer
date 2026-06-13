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

# transformers
trans = _stub_module("transformers")

class _FakeTokenizer:
    @classmethod
    def from_pretrained(cls, *args, **kwargs):
        return cls()

trans.AutoTokenizer = _FakeTokenizer
