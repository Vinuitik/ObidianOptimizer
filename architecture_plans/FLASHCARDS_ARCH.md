# Flashcards Creator Agent — Architecture Plan

Files: **IMPLEMENTED** — embedder/flashcards/{generate.py, validate.py, solver_sandbox.py}; Java cards/{CardRepository, CardGenerationService, CardJobWorker, CardController}; host-wrapper /complete (claude CLI). See cards/FLOWS.md.
[NOT IMPLEMENTED] — judge.py (open-answer verification), AssignmentService, BagDrawService, FsrsService, bandit, code-runner container, UI modes.

A **separate agent** from the ingest agent (deliberate — one agent, one job). Same philosophy: LLM only generates, behind a schema, with a capped retry budget; everything else — variant generation, answer verification, session draws, assignment assembly — is deterministic code.

```
note → Card generation (LLM, 1-2 calls) → Validation (deterministic, runs solvers)
     → cards in postgres
study time: Assignment builder (deterministic, point-budget knapsack)
          → per-answer verification (deterministic / cosine / banded judge)
          → attempt history
```

Split of responsibilities (matches existing app split):
- **Python (embedder container)**: card generation, solver sandbox execution, open-answer embedding verification, Haiku judge. New module `embedder/flashcards/`.
- **code-runner container** (new): executes user-submitted and LLM-reference code against tests, fully isolated (see Deployment & isolation).
- **Java backend**: card CRUD, bag draws, assignment assembly, attempt history, note-level FSRS scheduling. Calls embedder/runner over HTTP.

---

## Card types

### 1. `exercise` — parameterized, deterministically verifiable
The reusable "switch the problem around" card. The LLM emits a **template + parameter domains + a solver**, not a fixed question:

```json
{
  "type": "exercise",
  "template": "A list of {n} elements is sorted with bubble sort. How many comparisons happen in the worst case?",
  "params": { "n": { "kind": "int", "min": 4, "max": 12 } },
  "solver": "def solve(n):\n    return n * (n - 1) // 2",
  "answer_kind": "numeric",        
  "tolerance": 0,
  "conditions": [ { "name": "small", "values": {"n": 5} },
                  { "name": "exam-size", "values": {"n": 10} } ],
  "difficulty": 3
}
```

- **Variant generation is deterministic**: pick a named pre-created condition OR sample params uniformly from domains. Render template, ask user, compare input to `solve(params)` — numeric with tolerance, or normalized-string for `answer_kind: "string"`.
- **Solver execution** is sandboxed (see Technology Notes): AST-whitelisted (math/itertools only, no imports beyond allowlist, no IO), subprocess, 2s timeout.
- Code-comprehension questions ("what does this print") are exercises too — the answer is a value. Code-*writing* is its own card type below.

### 1b. `code` — user writes code, run against pre-made tests (LeetCode-style)
```json
{
  "type": "code",
  "language": "python",
  "statement": "Implement two_sum(nums, target) → indices of the two numbers adding to target.",
  "starter_code": "def two_sum(nums, target):\n    ...",
  "tests":        [ { "call": "two_sum([2,7,11,15], 9)", "expected": "[0, 1]" } ],
  "hidden_tests": [ { "call": "two_sum([3,3], 6)",       "expected": "[0, 1]" } ],
  "reference_solution": "def two_sum(nums, target): ...",
  "difficulty": 4
}
```
- Executed in the dedicated **code-runner container** (see Deployment & isolation) — not in the embedder, not via the AST-whitelist sandbox (real code needs real stdlib).
- **Generation validation** (deterministic, in the runner): reference_solution must pass ALL tests; starter_code must FAIL at least one (otherwise the card is broken or free points). Either failing → reject card, retry budget applies.
- **Verification**: user code runs against visible + hidden tests; verdict per test; `points_earned = round(difficulty × passed/total)` → PARTIAL verdicts are real here.
- Languages v1: **python** only. Java later — JVM startup per run and image size are the cost, not the design. `RUNNER_LANGS` gate.

### 2. `mcq` — single correct option
```json
{ "type": "mcq", "question": "…", "options": ["…","…","…","…"],
  "correct": 1, "difficulty": 2 }
```
Verification is trivial. Generation asks for plausible distractors (common misconceptions), not random noise.

### 3. `open` — free-text, judged
```json
{ "type": "open", "question": "…",
  "reference_answers": ["phrasing one", "phrasing two", "phrasing three"],
  "key_points": ["must mention X", "must mention Y"],
  "difficulty": 4 }
```
Verified by the banded cosine + judge scheme (below).

---

## Generation pipeline (LLM, constrained)

Trigger — **implemented as a hash-diff scan, not the queue table planned here**:
`CardJobWorker` periodically selects review notes (sr_due set) where
`notes.content_hash` has no matching ACTIVE `cards.source_hash` and no prior
attempt for that hash (`card_gen_attempts` ledger bounds retries). Same coverage
as the queue (app writes, sync downloads, chrono rewrites, external edits) with
zero call-site hooks. Batch-capped per pass (`cards.batch-limit`).

**DECIDED — LLM calls go through the host-wrapper's `claude` CLI endpoint
(`POST /complete`), NOT the Anthropic API.** The CLI bills the Claude subscription
(included credits); the API would bill separately. `SYNTH_MODEL` selects the model
(default haiku). The embedder never holds an API key for this.

```
note content (already-chunked text from note_chunks, or raw note)
  → PASS 1 — GENERATE (Haiku via wrapper CLI, 1 call): emit card set as schema-validated JSON
      target mix per note: ~N_MCQ mcq + ~N_OPEN open + ~N_EX exercises,
      difficulties spread 1–5 (prompt-enforced, validator-checked)
  → PASS 2 — BLIND SELF-CHECK (Haiku, 1 call, cheap): model answers its own
      mcqs/exercises WITHOUT seeing the marked answers; mismatch → drop card
      (known cheap quality filter for LLM-generated quizzes)
  → DETERMINISTIC VALIDATION (validate.py):
      schema; mcq: exactly one correct, options unique;
      exercise: solver passes AST whitelist, runs against ALL pre-created
      conditions + K random domain samples (default 10) without error and
      returns answer_kind-typed values; open: ≥2 reference answers
  → failures: re-prompt with the validation error, max 2 retries, then store
    what survived and log the rejects
  → upsert cards, keyed (note_path, card_hash); note content_hash stored
```

Re-generation on note change: cards whose source content hash still matches are kept (attempt history preserved); stale ones are archived (`status='ARCHIVED'`, attempts remain linked), new ones generated.

Cost shape: 2 Haiku calls per note, input = one note. Cents per vault, even regenerating often.

---

## Answer verification at study time

| Type | Mechanism | Tokens |
|---|---|---|
| mcq | index compare | 0 |
| exercise | run solver(params), compare with tolerance / normalized string | 0 |
| code | run in code-runner container vs visible+hidden tests; partial credit by tests passed | 0 |
| open | banded: embed user answer (mxbai, already running) → max cosine vs reference_answers. **≥ HIGH (0.85): correct. ≤ LOW (0.70): wrong. In between: Haiku judge** with key_points rubric → verdict + one-line feedback | 0 for clear cases; ~500 tokens for borderline |

The pure-cosine version (user's original idea) is the backbone; the judge band exists because embeddings are **negation-blind** — "X causes Y" and "X does not cause Y" land within a few cosine points of each other. See Technology Notes. Thresholds tunable; set `JUDGE_BAND=off` to run pure cosine.

---

## Sessions: bag draw + point-budget assignments (all deterministic)

### Bag draw (select-without-replacement, refill on exhaustion)
Per `(scope, type)` — scope = note / folder / tag selection:
```
cards.drawn_cycle INT DEFAULT 0          -- last cycle this card was drawn in
scope_state.current_cycle INT DEFAULT 1  -- per (scope, type)

draw(scope, type):
  eligible = active cards in scope+type WHERE drawn_cycle < current_cycle
  if eligible is empty → current_cycle += 1; re-query   (bag refilled)
  pick one uniformly at random → SET drawn_cycle = current_cycle → return it
```
Guarantees: every card seen once before any repeats; no persistence of a shuffle order needed (order is random within a cycle).

### Assignment builder (`AssignmentService`)
User asks: "assignment worth P points from scope S" (optionally with a type mix).
```
points per card = its difficulty (1–5)
→ greedy randomized fill: repeatedly bag-draw from each requested type
  (round-robin honoring the mix), until remaining budget < smallest difficulty
  available → final slot filled by drawing constrained to difficulty == remainder
  (fallback: closest available, assignment total may differ by ±1, reported)
→ assignment row + ordered card list; exercises get their variant rolled
  NOW (random or named condition) and frozen into the assignment
→ scoring: earned/total points; per-type and per-difficulty breakdown is the
  "do I actually understand this" feedback signal
```
Fixed point total + difficulty spread = comparable scores across sessions on the same scope. That comparability is the whole point — and it's what the FSRS layer below consumes.

[NOT IMPLEMENTED — upgrade]: difficulty re-calibration from observed success rates (a card everyone gets right drifts down a level). Schema leaves room (attempts table).

---

## FSRS layer — on NOTES, not cards

Standard FSRS input is the user self-rating recall of one card ("again/hard/good/easy") — vibes. Here, flashcards are the measurement layer underneath: an assignment produces an objective per-note score, and THAT feeds FSRS, which schedules **which notes are due for review**. Cards inside a session still use the bag (uniform coverage, comparable scores per note).

```
assignment finished (scope may span notes)
  → per-note sub-score: points earned / points possible over that note's cards
    (attempts → card_id → note_path, so this is a GROUP BY)
  → map score to band:  < 40% Hard · 40–70% Good · 70–90% Easy · ≥ 90% Very Easy
    (FSRS grades: Hard / Good / Easy / Easy — NO "Again"/lapse band, see below)
  → FSRS update on note_reviews row → new stability, difficulty, due date
  → bandit picks arm m ∈ {0.7, 0.85, 1.0, 1.2, 1.5} → due = now + interval × m
    (Option A — DECIDED: the multiplier applies to the OUTPUT interval only;
     the stored FSRS stability/difficulty state is never touched by the bandit)
  → GET /api/reviews/due → notes due now, ordered by overdue-ness
  → user (or one click) builds the next assignment scoped to due notes → loop
```

**DECIDED — no fire-on-fail.** The "Again" lapse band is deliberately absent: a bad
session grades as Hard (short interval) but never resets stability to near-zero.
Skipping days must not cascade into mass resets; overload is already managed by
BankruptcyService/SpreadService. Consequence to monitor: a genuinely forgotten note's
interval plateaus instead of shrinking — repeated <40% scores keep it at Hard spacing.
The strict-lapse alternative is parked in OPTIMIZATION_ARCH §5.

Grade thresholds: `AssignmentService.GRADE_BANDS` — this mapping is the tuning surface (see Technology Notes). A note with no cards yet is invisible to the queue; card generation is the price of entry.

**Two UI modes** (Settings toggle `flashcardsEnabled`):
- **OFF — slideshow**: due notes shown directly; user self-rates with the same four
  buttons (Hard / Good / Easy / Very Easy) → identical FSRS+bandit path, manual grade.
- **ON — test view**: separate view; mini-assignment drawn from the note's cards;
  band decided automatically from the score; note itself is openable at the end of
  the test for direct review.

---

## Database schema

```sql
CREATE TABLE cards (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  note_path     TEXT NOT NULL,
  type          TEXT NOT NULL CHECK (type IN ('mcq','open','exercise')),
  payload       JSONB NOT NULL,            -- full card JSON incl. solver/options/refs
  difficulty    INT NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
  card_hash     TEXT NOT NULL,             -- sha256 of payload, dedupe key
  source_hash   TEXT NOT NULL,             -- hash of source note content at gen time
  status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  drawn_cycle   INT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (note_path, card_hash)
);

CREATE TABLE pending_card_jobs (            -- mirror of pending_image_jobs
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  note_path TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('PENDING','DONE','SKIPPED')),
  created_at TIMESTAMP NOT NULL DEFAULT now(), processed_at TIMESTAMP
);

CREATE TABLE assignments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  scope TEXT NOT NULL, target_points INT NOT NULL, actual_points INT NOT NULL,
  card_ids UUID[] NOT NULL, variants JSONB,  -- frozen exercise params per card
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  card_id UUID NOT NULL REFERENCES cards(id),
  assignment_id UUID REFERENCES assignments(id),
  user_answer TEXT, verdict TEXT NOT NULL CHECK (verdict IN ('CORRECT','WRONG','PARTIAL')),
  judge_used BOOLEAN NOT NULL DEFAULT false, points_earned INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE scope_state (
  scope TEXT NOT NULL, type TEXT NOT NULL, current_cycle INT NOT NULL DEFAULT 1,
  PRIMARY KEY (scope, type)
);

CREATE TABLE note_reviews (              -- FSRS state, one row per note
  note_path   TEXT PRIMARY KEY,
  stability   REAL,
  difficulty  REAL,                      -- FSRS difficulty, NOT card difficulty
  reps        INT NOT NULL DEFAULT 0,
  lapses      INT NOT NULL DEFAULT 0,
  last_review TIMESTAMP,
  due         TIMESTAMP
);
CREATE INDEX ON note_reviews (due);
```

---

## Deployment & isolation — the code-runner container

LeetCode-grade-enough isolation without docker-in-docker (mounting the docker socket is host-root-equivalent — refused):

```yaml
code-runner:
  build: ./code-runner          # python:3.13-slim + tiny FastAPI: POST /run
  networks: [grader_net]        # ONLY this network
  read_only: true
  tmpfs: [/tmp:size=64m]        # code + harness written here per run
  user: "65534:65534"           # nobody
  mem_limit: 256m
  pids_limit: 64
  cpus: "1.0"

networks:
  grader_net:
    internal: true              # no internet egress, ever
```

Per run: write user code + JSON-I/O test harness to tmpfs → subprocess with wall-clock timeout (default 5s/test) and rlimits → compare outputs → `{passed, failed, errors[], stdout_truncated}`. No vault mount, no DB access, nothing to steal, nowhere to phone home. The Java backend is the only thing on `grader_net` besides the runner.

Worst case for malicious/broken code: burn 1 CPU for the timeout, OOM at 256MB, or fork-bomb into the pids cap. All self-limiting.

---

## API surface

| Endpoint | Owner | Does |
|---|---|---|
| `POST /api/cards/generate {note_path}` | Java → embedder `/flashcards/generate` | enqueue/force generation |
| `GET /api/cards?scope=…` | Java | list cards + stats |
| `POST /api/assignments {scope, points, mix?}` | Java | build assignment (bag draws + knapsack) |
| `POST /api/attempts {card_id, assignment_id?, answer, condition?}` | Java; open answers proxied to embedder `/flashcards/judge` | verify, record, return verdict + feedback |
| `GET /api/reviews/due` | Java | notes due per FSRS, ordered by overdue-ness |
| `POST /flashcards/generate` (internal) | embedder | LLM passes + validation, writes cards |
| `POST /flashcards/judge` (internal) | embedder | cosine band + optional Haiku judge |
| `POST /run` (internal, grader_net only) | code-runner | execute code vs tests, return per-test results |

MCP: optionally expose `generate_cards(note_path)` as an MCP tool later — same pattern as `ingest_resource`. [NOT IMPLEMENTED]

---

## Technology Notes

- **LLM-generated solver code is untrusted code.** v1 containment: AST parse → whitelist (arithmetic, comparisons, `math.*`, `itertools.*`, comprehensions; no `import`, no attribute access on dunder, no `exec/eval/open`), then run in a subprocess with 2s timeout and capped memory, inside the embedder container (no host filesystem beyond its mounts, vault mount read-only). This blocks accidents and casual escapes, not a determined attacker — acceptable for a single-user personal app where the "attacker" is Haiku being dumb. Do not loosen the whitelist before reading this sentence again.
- **Embedding cosine as answer judge — known failure modes**: negation blindness ("does NOT cause" ≈ "causes"), antonym proximity, and length bias (one-word answers score erratically vs sentence references). Hence the band: cosine settles only clear cases; the contested middle goes to a Haiku judge with the key_points rubric. Multiple reference answers per card materially improve cosine reliability — that's why the schema demands ≥2.
- **Blind self-check pass** catches the most common LLM quiz failure (the marked answer is wrong, or two MCQ options are both defensible) for one extra Haiku call. Dropped-card rate is also a free generation-quality metric — log it.
- **Two-layer scheduling (bag on cards, FSRS on notes)**: FSRS on individual cards would destroy score comparability (it deliberately overshows weak cards). Putting FSRS one level up — fed by objective note scores instead of self-rated recall — keeps both properties: uniform card coverage within a session, retention-optimized scheduling across notes.
- **FSRS caveats**: the algorithm's published parameters were trained on atomic-card self-ratings; feeding it aggregated note scores is non-standard. It will still behave sanely (the state update is just a function of grade + elapsed time) but the GRADE_BANDS thresholds are a guess until real history accumulates. Use default FSRS-6 parameters; personal parameter optimization needs months of review logs [NOT IMPLEMENTED]. Implementation: port the published FSRS-6 equations into Java (~50 lines of math, deterministic, unit-testable against py-fsrs outputs) — calling Python for a pure function isn't worth the hop.
- **Code-runner trust model**: inputs are LLM-generated cards and the user's own (vibe-coded) submissions — not hostile strangers. The container hardening (internal-only network, read-only fs, non-root, mem/pids/cpu caps, timeout) makes the worst case "wasted timeout," which is the right level of paranoia for a single-user app. It is NOT a public judge: no defense against kernel exploits or timing side channels, and don't add one.
- **JSON-I/O test harness** (not stdin parsing): the harness calls the user's function with literal args and compares repr/JSON of the return value. Float comparisons need tolerance; unordered collections need canonicalization (sort before compare) — harness helpers, written once, not per-card LLM output.
- **Exercise variant freezing**: variants are rolled at assignment build and stored in `assignments.variants` — re-opening an assignment shows the same numbers, and the attempt record is reproducible.
- **Difficulty is LLM-assigned and therefore noisy.** Treat v1 difficulty as a prior, not truth. The attempts table accumulates the data to recalibrate later; don't tune assignment fairness logic around exact difficulty correctness yet.
- **Regeneration vs attempt history**: cards are content-addressed (`card_hash`); regenerating an unchanged note is a no-op, changed notes archive stale cards instead of deleting — attempt history never dangles.

---

## Change Index

| Thing to change | Where (planned) |
|---|---|
| Cards-per-note mix | `flashcards/generate.py → N_MCQ / N_OPEN / N_EX` |
| Generation / self-check prompts | `flashcards/generate.py → GEN_PROMPT / CHECK_PROMPT` |
| Generation model | `SYNTH_MODEL` env var (shared with ingest; default claude-haiku-4-5) |
| Retry budget | `flashcards/generate.py → MAX_RETRIES` |
| Solver whitelist | `flashcards/solver_sandbox.py → ALLOWED_NODES / ALLOWED_NAMES` |
| Solver timeout / memory cap | `flashcards/solver_sandbox.py → TIMEOUT_S / MEM_MB` |
| Random validation samples | `flashcards/validate.py → K_SAMPLES` |
| Cosine bands | `flashcards/judge.py → COSINE_HIGH / COSINE_LOW`; `JUDGE_BAND=off` for pure cosine |
| Judge prompt | `flashcards/judge.py → JUDGE_PROMPT` |
| Points-per-difficulty | `AssignmentService` (currently 1:1) |
| Score→FSRS grade bands | `AssignmentService.GRADE_BANDS` (50/70/90) |
| FSRS parameters | `FsrsService.PARAMS` (FSRS-6 defaults) |
| Runner limits | compose `code-runner` (mem/pids/cpus) + `RUN_TIMEOUT_S` |
| Runner languages | `RUNNER_LANGS` (v1: python) |
| Worker schedule | `CardJobWorker @Scheduled(fixedDelay = …)` |
