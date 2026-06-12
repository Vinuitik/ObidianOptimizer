"""
Deterministic card validation — everything the LLM emits passes through here
before touching the database. Validation failures go back to the model as
re-prompt material (capped retries in generate.py); survivors are stored.
"""
import hashlib
import json
import random

from . import solver_sandbox

K_SAMPLES = 10  # random param draws per exercise on top of the named conditions

VALID_TYPES = {"mcq", "open", "exercise"}


def card_hash(card: dict) -> str:
    """Content-address: stable across runs, dedupe key with note_path."""
    canonical = json.dumps(card, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _check_difficulty(card: dict, errors: list[str]) -> None:
    d = card.get("difficulty")
    if not isinstance(d, int) or not 1 <= d <= 5:
        errors.append("difficulty must be an int 1-5")


def _validate_mcq(card: dict) -> list[str]:
    errors: list[str] = []
    if not isinstance(card.get("question"), str) or not card["question"].strip():
        errors.append("mcq: question required")
    options = card.get("options")
    if not isinstance(options, list) or not 2 <= len(options) <= 6:
        errors.append("mcq: 2-6 options required")
    elif len(set(map(str, options))) != len(options):
        errors.append("mcq: options must be unique")
    correct = card.get("correct")
    if not isinstance(correct, int) or not isinstance(options, list) \
            or not 0 <= correct < len(options or []):
        errors.append("mcq: correct must be a valid option index")
    _check_difficulty(card, errors)
    return errors


def _validate_open(card: dict) -> list[str]:
    errors: list[str] = []
    if not isinstance(card.get("question"), str) or not card["question"].strip():
        errors.append("open: question required")
    refs = card.get("reference_answers")
    if not isinstance(refs, list) or len([r for r in refs if isinstance(r, str) and r.strip()]) < 2:
        errors.append("open: at least 2 non-empty reference_answers required")
    _check_difficulty(card, errors)
    return errors


def _sample_params(domains: dict, rng: random.Random) -> dict:
    sample = {}
    for name, domain in domains.items():
        if domain.get("kind") == "int":
            sample[name] = rng.randint(domain["min"], domain["max"])
        elif domain.get("kind") == "choice":
            sample[name] = rng.choice(domain["values"])
        else:
            raise ValueError(f"unknown param kind for {name}")
    return sample


def _validate_exercise(card: dict) -> list[str]:
    errors: list[str] = []
    template = card.get("template")
    if not isinstance(template, str) or not template.strip():
        errors.append("exercise: template required")
    if card.get("answer_kind") not in ("numeric", "string"):
        errors.append("exercise: answer_kind must be numeric or string")
    domains = card.get("params")
    if not isinstance(domains, dict) or not domains:
        errors.append("exercise: params domains required")
    _check_difficulty(card, errors)

    solver = card.get("solver")
    if not isinstance(solver, str):
        errors.append("exercise: solver required")
        return errors
    try:
        solver_sandbox.check_solver(solver)
    except solver_sandbox.SandboxError as e:
        errors.append(f"exercise: solver rejected — {e}")
        return errors
    if errors:
        return errors

    # template must render with every param set, solver must run and return a
    # value of the declared kind — over all named conditions + K random samples
    rng = random.Random(0)  # deterministic validation
    trials = [c.get("values", {}) for c in card.get("conditions", []) or []]
    try:
        trials += [_sample_params(domains, rng) for _ in range(K_SAMPLES)]
    except (ValueError, KeyError, TypeError) as e:
        return [f"exercise: bad param domain — {e}"]

    for params in trials:
        try:
            template.format(**params)
        except (KeyError, IndexError) as e:
            errors.append(f"exercise: template render failed for {params} — {e}")
            break
        try:
            result = solver_sandbox.run_solver(solver, params)
        except solver_sandbox.SandboxError as e:
            errors.append(f"exercise: solver failed for {params} — {e}")
            break
        if card["answer_kind"] == "numeric" and not isinstance(result, (int, float)):
            errors.append(f"exercise: solver returned non-numeric {result!r} for {params}")
            break
        if card["answer_kind"] == "string" and not isinstance(result, str):
            errors.append(f"exercise: solver returned non-string {result!r} for {params}")
            break
    return errors


def validate_card(card: dict) -> list[str]:
    if not isinstance(card, dict):
        return ["card must be an object"]
    ctype = card.get("type")
    if ctype not in VALID_TYPES:
        return [f"unknown card type: {ctype!r}"]
    return {"mcq": _validate_mcq, "open": _validate_open,
            "exercise": _validate_exercise}[ctype](card)


def validate_cards(cards: list) -> tuple[list[dict], list[str]]:
    """Returns (valid_cards, all_error_strings)."""
    valid, errors = [], []
    if not isinstance(cards, list):
        return [], ["top-level output must be a JSON array of cards"]
    for i, card in enumerate(cards):
        card_errors = validate_card(card)
        if card_errors:
            errors.extend(f"card[{i}]: {e}" for e in card_errors)
        else:
            valid.append(card)
    return valid, errors
