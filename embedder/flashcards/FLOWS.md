# Flashcards (embedder) — FLOWS

Files: generate.py, validate.py, judge.py, solver_sandbox.py

## Generate
generate_for_note() → _with_image_descriptions() → GEN_PROMPT (+ _regen_preamble() on regen) → _complete() (host-wrapper /complete) → _extract_json() → validate.validate_cards() (retry ×MAX_RETRIES) → _self_check() → _normalize_angles() (logs mix) → _store() (cards.payload JSONB)
To change the prompt / angle rules: generate.GEN_PROMPT

## Angle
Each card carries `angle` in payload: what | why | how | where. Prompt asks ≤25% what, open never what, one example per angle.
_normalize_angles(): lowercases; unknown/missing or open+what → field dropped (no reject, no schema change). Mix logged as `[flashcards] <note> angle mix: {...}` and in the agent report ("self-check" step).
Existing cards keep old style until regenerated. [NOT IMPLEMENTED] enforcement of the 25% cap (prompt-only, observed via log).
To change valid angles: generate.ANGLES

## Technology Notes
- Mix is LLM-obeyed, not enforced; haiku may drift — check the log line.
- Prompt uses str.format: literal braces in GEN_PROMPT must be doubled.

## Change Index
| Thing | Where |
|---|---|
| Generation prompt + angle definitions/examples | generate.GEN_PROMPT |
| Allowed angles / drop rules / mix log | generate.ANGLES, _normalize_angles() |
| Regen prompt prefix (no own copy of schema) | generate._regen_preamble() |
| Card validation | validate.validate_card() |
| Wrapper URL / model | env WRAPPER_URL, SYNTH_MODEL |
