# Flashcards (embedder) — FLOWS

Files: generate.py, validate.py, judge.py, solver_sandbox.py, ../main.py (JudgeRequest, /flashcards/judge)

## Generate
generate_for_note() → _with_image_descriptions() → GEN_PROMPT (+ _regen_preamble() on regen) → _complete() (host-wrapper /complete) → _extract_json() → validate.validate_cards() (retry ×MAX_RETRIES) → _self_check() → _normalize_angles() (logs mix) → _store() (cards.payload JSONB)
To change the prompt / angle rules: generate.GEN_PROMPT

## Angle
Each card carries `angle` in payload: what | why | how | where. Prompt asks ≤25% what, open never what, one example per angle.
_normalize_angles(): lowercases; unknown/missing or open+what → field dropped (no reject, no schema change). Mix logged as `[flashcards] <note> angle mix: {...}` and in the agent report ("self-check" step).
Existing cards keep old style until regenerated. [NOT IMPLEMENTED] enforcement of the 25% cap (prompt-only, observed via log).
To change valid angles: generate.ANGLES

## Judge (open answers)

```
POST /flashcards/judge {question, answer, reference_answers, key_points?, llm=true}
  -> main.judge_answer() -> judge.judge_open_answer(..., llm)
  -> embed_texts([answer]+refs) -> similarity = max cosine
  -> >= COSINE_HIGH (0.85) -> CORRECT  | <= COSINE_LOW (0.30) -> WRONG   (judge_used=false)
  -> mid band: llm=false or JUDGE_BAND=off -> PARTIAL (judge_used=false, no LLM call)
  -> mid band, llm=true -> judge._complete_fast(): POST {WRAPPER_URL}/complete
       {tier:"fast", priority:"high"} timeout JUDGE_LLM_TIMEOUT_S (15s)
     -> _extract_json -> verdict/feedback (judge_used=true)
     -> any failure/bad JSON -> PARTIAL (judge_used=false)
```
Caller (cards/AssignmentService) calls llm=false first (~0.4s), then llm=true in the background.
To change: band thresholds `COSINE_HIGH/LOW`; LLM timeout `JUDGE_LLM_TIMEOUT_S`; prompt `JUDGE_PROMPT`.

## Technology Notes
- Mix is LLM-obeyed, not enforced; haiku may drift — check the log line.
- Prompt uses str.format: literal braces in GEN_PROMPT must be doubled.
- The judge LLM call is deliberately separate from `generate._complete` (240s timeout, big SYNTH_MODEL, medium priority) used by card synthesis.
- Embeddings are negation-blind; the mid band exists for that. llm=false therefore returns PARTIAL for anything contested - it is a provisional verdict, not a grade of record.
- Sync endpoint (`def`, FastAPI threadpool): a hung wrapper holds a worker thread up to 15s.
- Host-wrapper down -> `httpx` error -> PARTIAL fallback, logged `[judge] LLM judge failed`.

## Change Index
| Thing | Where |
|---|---|
| Generation prompt + angle definitions/examples | generate.GEN_PROMPT |
| Allowed angles / drop rules / mix log | generate.ANGLES, _normalize_angles() |
| Regen prompt prefix (no own copy of schema) | generate._regen_preamble() |
| Card validation | validate.validate_card() |
| Wrapper URL / model | env WRAPPER_URL, SYNTH_MODEL |
| Cosine thresholds | `judge.py -> COSINE_HIGH / COSINE_LOW` (env) |
| Judge LLM timeout | `judge.py -> JUDGE_LLM_TIMEOUT_S` (env, default 15) |
| Judge model/tier/priority | `judge._complete_fast()`; models in host-wrapper `LLM_FAST_ORDER` |
| Cosine-only switch | request field `llm` (`main.JudgeRequest`), env `JUDGE_BAND=off` |
| Judge prompt | `judge.JUDGE_PROMPT` |
