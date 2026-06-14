"""
Flashcard generation agent.

LLM calls go through the host-wrapper's /complete endpoint, which routes through
the LLM router: free-tier providers (Groq/GitHub/Mistral/...) first, the `claude`
CLI (subscription credits, never the Anthropic API) only as the last resort
(see host-wrapper/FLOWS.md). The "model" field only applies if the CLI is reached.

Pipeline per note:
  PASS 1  generate cards as JSON (schema in prompt)
  PASS 2  blind self-check: model re-answers its own mcq/exercise questions
          WITHOUT seeing the marked answers; mismatches are dropped
  validate.py — deterministic schema + solver checks; failures re-prompted
          with the error list, MAX_RETRIES budget
  upsert survivors into postgres (keyed note_path+card_hash); cards from older
          versions are kept ACTIVE (never auto-archived) — removal is user-only
"""
import json
import logging
import os
import re

import httpx
import psycopg

from . import validate
from .solver_sandbox import SandboxError, run_solver

log = logging.getLogger("embedder.flashcards")

WRAPPER_URL = os.environ.get("WRAPPER_URL", "http://host.docker.internal:5001")
SYNTH_MODEL = os.environ.get("SYNTH_MODEL", "haiku")
DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://obsidian:obsidian@postgres:5432/obsidian")

MAX_RETRIES = 2
N_MCQ, N_OPEN, N_EX = 3, 2, 2

GEN_PROMPT = """You generate study flashcards from a note. Output ONLY a JSON array, no prose.

Generate about {n_mcq} mcq, {n_open} open, {n_ex} exercise cards. Spread difficulty 1-5.
Only create exercise cards when the note contains computable/parameterizable material;
otherwise produce more mcq/open instead.

Card schemas:
mcq:      {{"type":"mcq","question":str,"options":[str,2-6 unique],"correct":int_index,"difficulty":1-5}}
          Distractors must be plausible misconceptions, not noise.
open:     {{"type":"open","question":str,"reference_answers":[str,str,...>=2 distinct phrasings],
           "key_points":[str,...],"difficulty":1-5}}
exercise: {{"type":"exercise","template":"text with {{param}} placeholders","params":{{"param":{{"kind":"int","min":int,"max":int}} or {{"kind":"choice","values":[...]}}}},
           "solver":"def solve(param): return ...","answer_kind":"numeric"|"string","tolerance":number,
           "conditions":[{{"name":str,"values":{{...}}}}],"difficulty":1-5}}
          Solver constraints: pure python, only math/itertools and basic builtins,
          no imports, no IO. solve() args must match params keys exactly.

NOTE:
{content}"""

CHECK_PROMPT = """Answer these quiz questions. Output ONLY a JSON object mapping question id to answer:
for mcq the option index (int), for numeric the number, for string the exact short answer string.

{questions}"""


# ── LLM via host-wrapper CLI ──────────────────────────────────────────────────

def _complete(prompt: str) -> str:
    resp = httpx.post(f"{WRAPPER_URL}/complete",
                      json={"prompt": prompt, "model": SYNTH_MODEL},
                      timeout=240.0)
    resp.raise_for_status()
    return resp.json()["text"]


def _extract_json(text: str):
    """Models love code fences; strip them and grab the outermost JSON value."""
    text = text.strip()
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.DOTALL)
    if fence:
        text = fence.group(1)
    start = min((i for i in (text.find("["), text.find("{")) if i >= 0), default=0)
    return json.loads(text[start:])


# ── Pass 2: blind self-check ─────────────────────────────────────────────────

def _self_check(cards: list[dict]) -> list[dict]:
    """Re-answer mcq/exercise questions blind; drop cards the model gets wrong."""
    checkable = []
    for i, card in enumerate(cards):
        if card["type"] == "mcq":
            checkable.append((i, {"id": i, "kind": "mcq",
                                  "question": card["question"], "options": card["options"]}))
        elif card["type"] == "exercise":
            params = (card.get("conditions") or [{}])[0].get("values") \
                or validate._sample_params(card["params"], __import__("random").Random(1))
            try:
                expected = run_solver(card["solver"], params)
            except SandboxError:
                continue  # validation already vouched; don't double-drop here
            checkable.append((i, {"id": i, "kind": card["answer_kind"],
                                  "question": card["template"].format(**params),
                                  "_expected": expected}))
    if not checkable:
        return cards

    questions = [{k: v for k, v in q.items() if k != "_expected"} for _, q in checkable]
    try:
        answers = _extract_json(_complete(CHECK_PROMPT.format(
            questions=json.dumps(questions, ensure_ascii=False))))
    except Exception as e:
        log.warning("[flashcards] self-check pass failed (%s) — keeping all cards", e)
        return cards

    dropped = set()
    for idx, q in checkable:
        given = answers.get(str(q["id"]), answers.get(q["id"]) if isinstance(answers, dict) else None)
        if q["kind"] == "mcq":
            ok = given == cards[idx]["correct"]
        elif q["kind"] == "numeric":
            tol = cards[idx].get("tolerance", 0) or 0
            try:
                ok = abs(float(given) - float(q["_expected"])) <= tol
            except (TypeError, ValueError):
                ok = False
        else:
            ok = isinstance(given, str) and given.strip().lower() == str(q["_expected"]).strip().lower()
        if not ok:
            dropped.add(idx)
            log.info("[flashcards] self-check dropped card %d (%s)", idx, q["kind"])

    return [c for i, c in enumerate(cards) if i not in dropped]


# ── Persistence ───────────────────────────────────────────────────────────────

def ensure_schema(conn) -> None:
    conn.execute("""
        CREATE TABLE IF NOT EXISTS cards (
          id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          note_path     TEXT NOT NULL,
          type          TEXT NOT NULL CHECK (type IN ('mcq','open','exercise')),
          payload       JSONB NOT NULL,
          difficulty    INT NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
          card_hash     TEXT NOT NULL,
          source_hash   TEXT NOT NULL,
          status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
          drawn_cycle   INT NOT NULL DEFAULT 0,
          created_at    TIMESTAMP NOT NULL DEFAULT now(),
          UNIQUE (note_path, card_hash)
        )""")


def _store(note_path: str, source_hash: str, cards: list[dict]) -> dict:
    with psycopg.connect(DATABASE_URL) as conn:
        ensure_schema(conn)
        # NOTE: cards from older versions of this note are intentionally NOT archived.
        # Edits are usually additive (more structure), so old cards stay valid and
        # remain in the ACTIVE pool the review draw samples from. Removal is user-only
        # (explicit delete) — never automatic. New cards are added/deduped below.
        archived = 0
        inserted = 0
        for card in cards:
            ch = validate.card_hash(card)
            cur = conn.execute("""
                INSERT INTO cards (note_path, type, payload, difficulty, card_hash, source_hash)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (note_path, card_hash) DO UPDATE SET
                  source_hash = EXCLUDED.source_hash, status = 'ACTIVE'
                """, (note_path, card["type"], json.dumps(card), card["difficulty"], ch, source_hash))
            inserted += cur.rowcount
        conn.commit()
    return {"stored": inserted, "archived": archived}


# ── Entry point ───────────────────────────────────────────────────────────────

def generate_for_note(note_path: str, content: str, source_hash: str) -> dict:
    prompt = GEN_PROMPT.format(n_mcq=N_MCQ, n_open=N_OPEN, n_ex=N_EX, content=content)
    valid: list[dict] = []
    errors: list[str] = []

    for attempt in range(1 + MAX_RETRIES):
        try:
            raw = _complete(prompt if attempt == 0 else
                            prompt + "\n\nYour previous output had these validation errors — fix them:\n"
                            + "\n".join(errors[:20]))
            cards = _extract_json(raw)
        except (httpx.HTTPError, json.JSONDecodeError, ValueError) as e:
            errors = [f"output was not parseable JSON: {e}"]
            log.warning("[flashcards] %s attempt %d: %s", note_path, attempt, errors[0])
            continue
        valid, errors = validate.validate_cards(cards)
        if valid and not errors:
            break
        log.info("[flashcards] %s attempt %d: %d valid, %d errors",
                 note_path, attempt, len(valid), len(errors))
        if valid and attempt == MAX_RETRIES:
            break  # store what survived, log the rejects

    if not valid:
        return {"note_path": note_path, "stored": 0, "archived": 0,
                "rejected": len(errors), "errors": errors[:10]}

    before = len(valid)
    valid = _self_check(valid)
    result = _store(note_path, source_hash, valid)
    result.update({"note_path": note_path,
                   "self_check_dropped": before - len(valid),
                   "rejected": len(errors)})
    return result
