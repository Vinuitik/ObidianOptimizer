"""
Open-answer verification: banded cosine + LLM judge for the contested middle.

Embeddings are negation-blind ("X causes Y" ≈ "X does not cause Y"), so pure
cosine only settles CLEAR cases: >= HIGH is correct, <= LOW is wrong. The band
between goes to a judge call through the host-wrapper CLI with the card's
key_points rubric. JUDGE_BAND=off degrades to pure cosine (middle → PARTIAL).
"""
import json
import logging
import os
import re

import numpy as np

from model_runtime import embed_texts

log = logging.getLogger("embedder.flashcards.judge")

COSINE_HIGH = float(os.environ.get("COSINE_HIGH", "0.85"))
COSINE_LOW = float(os.environ.get("COSINE_LOW", "0.70"))
JUDGE_BAND = os.environ.get("JUDGE_BAND", "on").lower() != "off"

JUDGE_PROMPT = """Grade a student's answer against the rubric. Output ONLY JSON:
{{"verdict": "CORRECT"|"PARTIAL"|"WRONG", "feedback": "one short sentence"}}

Question: {question}
Key points the answer must cover: {key_points}
Reference answers: {references}
Student answer: {answer}"""


def judge_open_answer(question: str, answer: str, reference_answers: list[str],
                      key_points: list[str] | None = None) -> dict:
    """Returns {"verdict": CORRECT|PARTIAL|WRONG, "similarity": float,
                "judge_used": bool, "feedback": str|None}."""
    if not answer or not answer.strip():
        return {"verdict": "WRONG", "similarity": 0.0, "judge_used": False, "feedback": None}

    vectors = np.array(embed_texts([answer] + list(reference_answers)))
    similarity = float(max(vectors[0] @ ref for ref in vectors[1:]))  # already L2-normalised

    if similarity >= COSINE_HIGH:
        return {"verdict": "CORRECT", "similarity": similarity, "judge_used": False, "feedback": None}
    if similarity <= COSINE_LOW:
        return {"verdict": "WRONG", "similarity": similarity, "judge_used": False, "feedback": None}
    if not JUDGE_BAND:
        return {"verdict": "PARTIAL", "similarity": similarity, "judge_used": False, "feedback": None}

    from .generate import _complete, _extract_json  # late import: shares wrapper plumbing
    try:
        out = _extract_json(_complete(JUDGE_PROMPT.format(
            question=question, answer=answer,
            key_points=json.dumps(key_points or []),
            references=json.dumps(reference_answers))))
        verdict = str(out.get("verdict", "")).upper()
        if verdict not in ("CORRECT", "PARTIAL", "WRONG"):
            raise ValueError(f"bad verdict {verdict!r}")
        return {"verdict": verdict, "similarity": similarity,
                "judge_used": True, "feedback": out.get("feedback")}
    except Exception as e:
        log.warning("[judge] LLM judge failed (%s) — falling back to PARTIAL", e)
        return {"verdict": "PARTIAL", "similarity": similarity, "judge_used": False, "feedback": None}


def normalize_string(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip().lower())
