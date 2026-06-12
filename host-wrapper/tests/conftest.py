"""
Test env must exist before main/llm_router import: main.py requires
HOST_VAULT_PATH and llm_router reads keys/limits at import time.
Keys are fakes — no test in this suite may hit a real provider.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

os.environ.setdefault("HOST_VAULT_PATH", str(Path(__file__).parent / "vault"))
os.environ["LLM_ACQUIRE_DEADLINE_S"] = "2"   # fail fast in lease-starvation tests
os.environ["GEMINI_API_KEY"] = "test-gemini"
os.environ["GITHUB_MODELS_TOKEN"] = "test-github"
os.environ["MISTRAL_API_KEY"] = "test-mistral"
os.environ["GROQ_API_KEY"] = "test-groq"
os.environ["DEEPSEEK_API_KEY"] = ""          # unconfigured on purpose
os.environ["ANTHROPIC_API_KEY"] = ""         # unconfigured on purpose

import pytest  # noqa: E402

import llm_router  # noqa: E402


@pytest.fixture
def router():
    """Fresh Router per test — provider state (cooldowns, counters) is mutable."""
    return llm_router.Router()


@pytest.fixture
def no_rate_spacing(monkeypatch):
    """Zero out min_interval so tests never sleep waiting for next_start."""
    def patch(r):
        for p in r.providers.values():
            p.min_interval = 0.0
        return r
    return patch
