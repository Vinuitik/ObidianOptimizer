# The Determinism Boundary

The highest-leverage design question for any agent: **for each step, can deterministic code do it?** If yes, the LLM must not. Push the boundary so the model's surface area is minimal and everything crossing it is a typed contract.

## The procedure

1. List every step of the task end to end.
2. Mark each: *mechanical* (code), *judgment* (model), or *mixed*.
3. Split every *mixed* step until nothing is mixed.
4. Define a JSON contract at every code↔model crossing.
5. Validate at every crossing, both directions.

## Worked examples

**Ingest agent** — looks like an "AI task" (watch a video, take notes) but decomposes almost entirely into code:
- transcripts: yt-dlp captions / whisper → *code*
- which video frames matter: scene delta + transcript cue regex + CLIP filter → *code with pretrained models* (pretrained ≠ LLM; CLIP is deterministic at inference)
- where images go in the note: interleave by timestamp → *code*
- what the note says, how to split topics: → *model*, behind two schema-validated calls

**Flashcards** — answer verification: mcq = index compare (*code*), exercises = run the solver (*code*), code cards = run the tests (*code*); only contested open-ended answers reach a model judge, and only in the cosine band where code admits it can't decide.

## The contract pattern ("bundle")

Define one normalized intermediate representation per pipeline — the ingest agent's Extraction Bundle, the Kaggle agent's Data Profile. All extractors emit it; the model only ever reads it. Benefits: the model's input is testable, cacheable, diffable; extractors are swappable; and you can re-run the model stage without re-running extraction (re-synthesize a note on a better model for cents).

## Why bother (the variance argument)

Every probabilistic step multiplies failure modes: a 5-step chain of 95%-reliable LLM steps is ~77% reliable; the same chain with 4 deterministic steps and 1 LLM step is ~95%. Determinism isn't aesthetic — it's compounding reliability. It's also where the money goes: deterministic steps cost zero tokens forever.

See also [[Harness Engineering]], [[Workflows vs Agents]].
