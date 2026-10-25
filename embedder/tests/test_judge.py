"""Judge + roll endpoint tests — embeddings and the LLM judge are mocked."""
import numpy as np
import pytest
from fastapi.testclient import TestClient

import main as embedder_main
from flashcards import judge


@pytest.fixture
def client():
    return TestClient(embedder_main.app, base_url="http://localhost")


def _mock_embeddings(monkeypatch, similarity):
    """First vector = answer; one reference at the requested cosine."""
    ref = np.zeros(8); ref[0] = 1.0
    ans = np.zeros(8); ans[0] = similarity; ans[1] = np.sqrt(max(0, 1 - similarity ** 2))
    monkeypatch.setattr(judge, "embed_texts", lambda texts: [ans.tolist(), ref.tolist()])


def test_high_cosine_is_correct_without_judge(monkeypatch):
    _mock_embeddings(monkeypatch, 0.95)
    out = judge.judge_open_answer("Q", "an answer", ["reference"])
    assert out["verdict"] == "CORRECT" and not out["judge_used"]


def test_low_cosine_is_wrong_without_judge(monkeypatch):
    _mock_embeddings(monkeypatch, 0.20)
    out = judge.judge_open_answer("Q", "an answer", ["reference"])
    assert out["verdict"] == "WRONG" and not out["judge_used"]


def test_middle_band_goes_to_llm_judge(monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)
    monkeypatch.setattr(judge, "_complete_fast",
                        lambda p: '{"verdict": "WRONG", "feedback": "misses negation"}')
    out = judge.judge_open_answer("Q", "X causes Y", ["X does not cause Y"], ["negation"])
    assert out["verdict"] == "WRONG" and out["judge_used"]
    assert out["feedback"] == "misses negation"


def test_middle_band_judge_failure_falls_back_partial(monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)
    monkeypatch.setattr(judge, "_complete_fast", lambda p: "not json")
    out = judge.judge_open_answer("Q", "answer", ["reference"])
    assert out["verdict"] == "PARTIAL" and not out["judge_used"]


def test_llm_false_mid_band_is_partial_without_call(monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)

    def boom(p):
        raise AssertionError("LLM must not be called")
    monkeypatch.setattr(judge, "_complete_fast", boom)
    out = judge.judge_open_answer("Q", "a", ["r"], llm=False)
    assert out["verdict"] == "PARTIAL" and not out["judge_used"]
    assert out["similarity"] == pytest.approx(0.78, abs=1e-3)


def test_llm_false_clear_bands_unchanged(monkeypatch):
    _mock_embeddings(monkeypatch, 0.95)
    assert judge.judge_open_answer("Q", "a", ["r"], llm=False)["verdict"] == "CORRECT"
    _mock_embeddings(monkeypatch, 0.1)
    assert judge.judge_open_answer("Q", "a", ["r"], llm=False)["verdict"] == "WRONG"


def test_judge_llm_call_uses_fast_tier_high_priority_short_timeout(monkeypatch):
    import httpx
    seen = {}

    class R:
        def raise_for_status(self): pass
        def json(self): return {"text": "ok"}

    def fake_post(url, json=None, timeout=None):
        seen.update(url=url, json=json, timeout=timeout)
        return R()
    monkeypatch.setattr(httpx, "post", fake_post)
    assert judge._complete_fast("p") == "ok"
    assert seen["json"]["tier"] == "fast" and seen["json"]["priority"] == "high"
    assert seen["timeout"] == judge.JUDGE_LLM_TIMEOUT_S == 15.0
    assert "model" not in seen["json"]


def test_endpoint_passes_llm_flag(client, monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)

    def boom(p):
        raise AssertionError("no LLM")
    monkeypatch.setattr(judge, "_complete_fast", boom)
    r = client.post("/flashcards/judge", json={"question": "Q", "answer": "a",
                                               "reference_answers": ["r"], "llm": False})
    assert r.status_code == 200 and r.json()["verdict"] == "PARTIAL"


def test_empty_answer_is_wrong():
    assert judge.judge_open_answer("Q", "  ", ["ref"])["verdict"] == "WRONG"


EXERCISE = {"template": "Comparisons for {n} elements?",
            "params": {"n": {"kind": "int", "min": 4, "max": 6}},
            "solver": "def solve(n):\n    return n * (n - 1) // 2",
            "answer_kind": "numeric",
            "conditions": [{"name": "small", "values": {"n": 5}}]}


def test_roll_named_condition(client):
    resp = client.post("/flashcards/roll", json={"payload": EXERCISE, "condition": "small"})
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"params": {"n": 5}, "rendered": "Comparisons for 5 elements?", "expected": 10}


def test_roll_random_sample_stays_in_domain(client):
    resp = client.post("/flashcards/roll", json={"payload": EXERCISE})
    body = resp.json()
    n = body["params"]["n"]
    assert 4 <= n <= 6 and body["expected"] == n * (n - 1) // 2


def test_roll_unknown_condition_422(client):
    resp = client.post("/flashcards/roll", json={"payload": EXERCISE, "condition": "huge"})
    assert resp.status_code == 422
