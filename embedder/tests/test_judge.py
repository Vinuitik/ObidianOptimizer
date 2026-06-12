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
    _mock_embeddings(monkeypatch, 0.30)
    out = judge.judge_open_answer("Q", "an answer", ["reference"])
    assert out["verdict"] == "WRONG" and not out["judge_used"]


def test_middle_band_goes_to_llm_judge(monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)
    from flashcards import generate
    monkeypatch.setattr(generate, "_complete",
                        lambda p: '{"verdict": "WRONG", "feedback": "misses negation"}')
    out = judge.judge_open_answer("Q", "X causes Y", ["X does not cause Y"], ["negation"])
    assert out["verdict"] == "WRONG" and out["judge_used"]
    assert out["feedback"] == "misses negation"


def test_middle_band_judge_failure_falls_back_partial(monkeypatch):
    _mock_embeddings(monkeypatch, 0.78)
    from flashcards import generate
    monkeypatch.setattr(generate, "_complete", lambda p: "not json")
    out = judge.judge_open_answer("Q", "answer", ["reference"])
    assert out["verdict"] == "PARTIAL" and not out["judge_used"]


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
