# Embedder Flashcards - Open-Answer Judge Flow

Files: judge.py, generate.py (WRAPPER_URL, _extract_json), ../main.py (JudgeRequest, /flashcards/judge)

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
- The judge LLM call is deliberately separate from `generate._complete` (240s timeout, big SYNTH_MODEL, medium priority) used by card synthesis.
- Embeddings are negation-blind; the mid band exists for that. llm=false therefore returns PARTIAL for anything contested - it is a provisional verdict, not a grade of record.
- Sync endpoint (`def`, FastAPI threadpool): a hung wrapper holds a worker thread up to 15s.
- Host-wrapper down -> `httpx` error -> PARTIAL fallback, logged `[judge] LLM judge failed`.

## Change Index
| Thing | Where |
|---|---|
| Cosine thresholds | `judge.py -> COSINE_HIGH / COSINE_LOW` (env) |
| Judge LLM timeout | `judge.py -> JUDGE_LLM_TIMEOUT_S` (env, default 15) |
| Judge model/tier/priority | `judge._complete_fast()`; models in host-wrapper `LLM_FAST_ORDER` |
| Cosine-only switch | request field `llm` (`main.JudgeRequest`), env `JUDGE_BAND=off` |
| Judge prompt | `judge.JUDGE_PROMPT` |
