"""
Flashcard agent tests — sandbox containment, deterministic validation, and the
generation pipeline with the wrapper LLM + DB mocked out.
"""
import json

import pytest

from flashcards import generate as gen
from flashcards import validate
from flashcards.solver_sandbox import SandboxError, check_solver, run_solver


@pytest.fixture(autouse=True)
def _no_image_db(monkeypatch):
    """generate_for_note enriches with image descriptions from the DB; in unit
    tests there's no DB, so default it to a no-op. Tests that exercise the
    formatting call _format_with_descriptions directly (pure, no I/O)."""
    monkeypatch.setattr(gen, "_with_image_descriptions", lambda note_path, content: content)


# ── Sandbox: what must pass ───────────────────────────────────────────────────

def test_sandbox_runs_simple_solver():
    code = "def solve(n):\n    return n * (n - 1) // 2"
    check_solver(code)
    assert run_solver(code, {"n": 5}) == 10


def test_sandbox_allows_math_and_itertools():
    code = ("def solve(n):\n"
            "    import_free = math.factorial(n)\n"
            "    pairs = len(list(itertools.combinations(range(n), 2)))\n"
            "    return import_free + pairs")
    check_solver(code)
    assert run_solver(code, {"n": 4}) == 24 + 6


# ── Sandbox: what must be rejected ────────────────────────────────────────────

@pytest.mark.parametrize("code", [
    "import os\ndef solve(n): return 1",                       # import
    "def solve(n): return open('/etc/passwd').read()",          # open() not in builtins
    "def solve(n): return eval('1+1')",                         # eval not allowed
    "def solve(n): return ().__class__",                        # dunder attribute
    "def solve(n): return n.bit_length()",                      # attribute off non-module
    "def solve(n):\n    with open('x') as f: return 1",         # with-statement
    "x = 1",                                                    # no solve()
])
def test_sandbox_rejects(code):
    with pytest.raises(SandboxError):
        check_solver(code)


def test_sandbox_timeout_kills_infinite_loop():
    code = "def solve(n):\n    while True:\n        n += 1\n    return n"
    check_solver(code)  # AST-legal — containment is the subprocess timeout
    with pytest.raises(SandboxError, match="timed out"):
        run_solver(code, {"n": 1})


# ── Validation ────────────────────────────────────────────────────────────────

def _mcq(**overrides):
    card = {"type": "mcq", "question": "2+2?", "options": ["3", "4", "5"],
            "correct": 1, "difficulty": 2}
    card.update(overrides)
    return card


def test_validate_mcq_happy():
    assert validate.validate_card(_mcq()) == []


@pytest.mark.parametrize("bad", [
    _mcq(correct=9),                       # index out of range
    _mcq(options=["same", "same"]),        # duplicate options
    _mcq(options=["only one"]),            # too few
    _mcq(difficulty=7),                    # difficulty range
    _mcq(question="  "),                   # blank question
])
def test_validate_mcq_rejects(bad):
    assert validate.validate_card(bad)


def test_validate_open_requires_two_references():
    card = {"type": "open", "question": "Why?",
            "reference_answers": ["only one"], "difficulty": 3}
    assert validate.validate_card(card)
    card["reference_answers"] = ["one phrasing", "another phrasing"]
    assert validate.validate_card(card) == []


def test_validate_exercise_runs_conditions_and_samples():
    card = {"type": "exercise",
            "template": "Bubble sort worst case comparisons for {n} elements?",
            "params": {"n": {"kind": "int", "min": 4, "max": 12}},
            "solver": "def solve(n):\n    return n * (n - 1) // 2",
            "answer_kind": "numeric", "tolerance": 0,
            "conditions": [{"name": "small", "values": {"n": 5}}],
            "difficulty": 3}
    assert validate.validate_card(card) == []


def test_validate_exercise_rejects_crashing_solver():
    card = {"type": "exercise", "template": "What is 1/{n}?",
            "params": {"n": {"kind": "int", "min": 0, "max": 0}},  # division by zero
            "solver": "def solve(n):\n    return 1 / n",
            "answer_kind": "numeric", "difficulty": 2}
    errors = validate.validate_card(card)
    assert any("solver failed" in e for e in errors)


def test_validate_exercise_rejects_template_param_mismatch():
    card = {"type": "exercise", "template": "Value of {missing}?",
            "params": {"n": {"kind": "int", "min": 1, "max": 3}},
            "solver": "def solve(n):\n    return n",
            "answer_kind": "numeric", "difficulty": 1}
    errors = validate.validate_card(card)
    assert any("template render failed" in e for e in errors)


def test_validate_exercise_rejects_literal_braces_without_crashing():
    # Regression: LLM exercise templates with UNescaped literal braces (LaTeX
    # \binom{}{}, code, set notation, a lone brace) make str.format raise
    # ValueError — which used to escape validation and 500 /flashcards/generate.
    # Must be rejected as a normal validation error, never raised.
    card = {"type": "exercise",
            "template": "Answer is } for a set of {n} items",   # lone '}' → ValueError
            "params": {"n": {"kind": "int", "min": 1, "max": 3}},
            "solver": "def solve(n):\n    return n",
            "answer_kind": "numeric", "difficulty": 1}
    errors = validate.validate_card(card)        # must NOT raise
    assert any("template render failed" in e for e in errors)


def test_validate_cards_never_raises_on_bad_card():
    # Safety net: a single pathological card must not crash the whole batch —
    # good cards still come back valid.
    valid, errors = validate.validate_cards([_mcq(), {"type": "exercise"}])
    assert len(valid) == 1 and errors


def test_card_hash_stable_and_distinct():
    a, b = _mcq(), _mcq()
    assert validate.card_hash(a) == validate.card_hash(b)
    assert validate.card_hash(a) != validate.card_hash(_mcq(question="3+3?"))


# ── Generation pipeline (wrapper + DB mocked) ─────────────────────────────────

GOOD_CARDS = [
    {"type": "mcq", "question": "What is FSRS?", "options": ["A scheduler", "A database"],
     "correct": 0, "difficulty": 2},
    {"type": "open", "question": "Explain stability.",
     "reference_answers": ["Days until recall drops to 90%", "Memory strength in days"],
     "difficulty": 3},
]


def test_generate_happy_path(monkeypatch):
    calls = []

    def fake_complete(prompt):
        calls.append(prompt)
        if prompt.startswith("Answer these quiz"):
            return json.dumps({"0": 0})          # correct self-check answer
        return json.dumps(GOOD_CARDS)

    stored = {}
    monkeypatch.setattr(gen, "_complete", fake_complete)
    monkeypatch.setattr(gen, "_store", lambda path, sh, cards: stored.update(
        {"path": path, "source_hash": sh, "cards": cards}) or {"stored": len(cards), "archived": 0})

    result = gen.generate_for_note("/vault/ml/FSRS.md", "note content", "hash123")
    assert result["stored"] == 2
    assert result["self_check_dropped"] == 0
    assert stored["source_hash"] == "hash123"
    assert len(calls) == 2  # generate + self-check


def test_generate_self_check_drops_wrong_mcq(monkeypatch):
    def fake_complete(prompt):
        if prompt.startswith("Answer these quiz"):
            return json.dumps({"0": 1})          # model picks the WRONG option blind
        return json.dumps(GOOD_CARDS)

    monkeypatch.setattr(gen, "_complete", fake_complete)
    monkeypatch.setattr(gen, "_store", lambda path, sh, cards: {"stored": len(cards), "archived": 0})

    result = gen.generate_for_note("/vault/n.md", "content", "h")
    assert result["self_check_dropped"] == 1
    assert result["stored"] == 1                  # only the open card survives


def test_generate_retries_on_validation_errors_then_stores_survivors(monkeypatch):
    bad_then_good = [
        json.dumps([_mcq(correct=9)]),                       # attempt 0 — invalid
        json.dumps(GOOD_CARDS),                              # attempt 1 — valid
        json.dumps({"0": 0}),                                # self-check
    ]
    monkeypatch.setattr(gen, "_complete", lambda p: bad_then_good.pop(0))
    monkeypatch.setattr(gen, "_store", lambda path, sh, cards: {"stored": len(cards), "archived": 0})

    result = gen.generate_for_note("/vault/n.md", "content", "h")
    assert result["stored"] == 2


def test_generate_gives_up_after_retry_budget(monkeypatch):
    monkeypatch.setattr(gen, "_complete", lambda p: "this is not json at all {{{")
    result = gen.generate_for_note("/vault/n.md", "content", "h")
    assert result["stored"] == 0
    assert result["errors"]


def test_generate_propagates_llm_unavailable_not_silent_zero(monkeypatch):
    """An unreachable/exhausted LLM must raise LLMUnavailable, NOT be swallowed
    into a stored:0 result — the on-demand path turns this into a loud 503."""
    def boom(prompt):
        raise gen.LLMUnavailable("host-wrapper unreachable")
    monkeypatch.setattr(gen, "_complete", boom)

    with pytest.raises(gen.LLMUnavailable):
        gen.generate_for_note("/vault/n.md", "content", "h")


def test_complete_raises_llm_unavailable_on_5xx(monkeypatch):
    class _Resp:
        status_code = 503
        text = "all providers exhausted"
    monkeypatch.setattr(gen.httpx, "post", lambda *a, **k: _Resp())
    with pytest.raises(gen.LLMUnavailable):
        gen._complete("prompt")


def test_complete_raises_llm_unavailable_on_transport_error(monkeypatch):
    def boom(*a, **k):
        raise gen.httpx.ConnectError("connection refused")
    monkeypatch.setattr(gen.httpx, "post", boom)
    with pytest.raises(gen.LLMUnavailable):
        gen._complete("prompt")


# ── Image-description enrichment (pure formatter) ─────────────────────────────

def test_format_with_descriptions_appends_labelled_block():
    out = gen._format_with_descriptions("Note body.", ["A flowchart of the FSRS loop",
                                                       "Screenshot: error 422"])
    assert "Note body." in out
    assert "A flowchart of the FSRS loop" in out
    assert "Screenshot: error 422" in out
    assert "Image contents" in out


def test_format_with_descriptions_empty_or_blank_leaves_content_unchanged():
    assert gen._format_with_descriptions("Body.", []) == "Body."
    assert gen._format_with_descriptions("Body.", ["  ", ""]) == "Body."


def test_extract_json_handles_code_fences():
    fenced = "```json\n[{\"a\": 1}]\n```"
    assert gen._extract_json(fenced) == [{"a": 1}]
    assert gen._extract_json("Sure! Here you go: [1, 2]") == [1, 2]
