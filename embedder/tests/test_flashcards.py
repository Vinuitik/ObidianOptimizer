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
    tests there's no DB, so default it to no rows. Tests that exercise the
    formatting call _format_with_descriptions directly (pure, no I/O)."""
    monkeypatch.setattr(gen, "_fetch_image_rows", lambda note_path: [])
    # Trace writing posts to the Java backend; keep unit tests off the network.
    monkeypatch.setattr(gen, "_write_trace", lambda note_path, markdown: None)


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


# ── Generation trace (observability) ──────────────────────────────────────────

def test_build_trace_md_shows_input_images_and_raw_output():
    md = gen._build_trace_md(
        "/vault/ml/FSRS.md",
        assembled_input="The note body the model saw.",
        image_rows=[{"text": "A flowchart of the FSRS loop",
                     "image_path": "resources/images/fsrs.png", "provider": "gemini"}],
        attempts=[{"attempt": 0, "raw": "[{\"type\":\"mcq\"}]", "valid": 1, "errors": []}],
        self_check_dropped=2,
        result={"stored": 1, "rejected": 0})
    # the exact input the model saw
    assert "The note body the model saw." in md
    # image attributed to its source file + provider (the downstream-error trail)
    assert "resources/images/fsrs.png" in md and "gemini" in md
    assert "A flowchart of the FSRS loop" in md
    # raw reply + outcome
    assert "[{\"type\":\"mcq\"}]" in md
    assert "self-check dropped: 2" in md


def test_generate_writes_trace_with_image_provenance(monkeypatch):
    monkeypatch.setattr(gen, "_fetch_image_rows", lambda note_path: [
        {"text": "Bad OCR mush æø", "image_path": "img/diagram.png", "provider": "groq"}])

    def fake_complete(prompt):
        # assembled input must carry the image text into the prompt
        assert "Bad OCR mush" in prompt or prompt.startswith("Answer these quiz")
        if prompt.startswith("Answer these quiz"):
            return json.dumps({"0": 0})
        return json.dumps(GOOD_CARDS)

    captured = {}
    monkeypatch.setattr(gen, "_complete", fake_complete)
    monkeypatch.setattr(gen, "_store", lambda p, sh, c: {"stored": len(c), "archived": 0})
    monkeypatch.setattr(gen, "_write_trace",
                        lambda note_path, markdown: captured.update(md=markdown))

    gen.generate_for_note("/vault/n.md", "content", "h")
    assert "img/diagram.png" in captured["md"] and "groq" in captured["md"]
    assert "Bad OCR mush" in captured["md"]
