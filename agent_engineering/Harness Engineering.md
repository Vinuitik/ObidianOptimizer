# Harness Engineering

**The harness is everything around the model**: the deterministic program that feeds it context, executes its tool calls, validates its outputs, enforces budgets, persists state, and decides when the loop ends. The model is a stateless text function; the harness is the actual software.

When people say a coding agent "got better," half the time the *model* didn't change — the harness did. Same model, better harness → dramatically better agent. That's why it's an engineering discipline with its own name.

## What a harness owns (checklist)

- **The loop itself** — when to call the model, when to stop ([[Budgets and Failure Modes]])
- **Tool execution** — parse the call, run it, format the result ([[Tool Design for Agents]])
- **Validation** — schema-check every structured output; on failure, feed the *error text* back and retry with a cap. The error message is a prompt.
- **State & memory** — what persists between iterations. Best practice: a ledger/database the prompt is *rebuilt from* each turn, not an ever-growing chat transcript ([[Context Engineering]])
- **Guards** — reject illegal actions *before* execution. Kaggle agent: CV scheme is immutable, no transform may touch test-fold targets, duplicate experiments rejected by hash. The model literally cannot cheat, regardless of what it "wants."
- **Idempotency & resume** — every step re-runnable; crash recovery = re-read the ledger
- **Observability** — log every model call, tool call, token count, decision. Agents fail silently in weird ways; the ledger is how you debug them.
- **Safety rails** — sandboxes for code, allowlists for paths/commands ([[Sandboxing LLM and User Code]])

## The asymmetry that makes this work

The model is good at *judgment* (what to try, how to phrase, what matters) and bad at *invariants* (never peek at test data, always emit valid JSON, never exceed the budget). Code is the exact opposite. Harness engineering is just putting each job where it's reliably done:

> Model proposes. Harness disposes.

## Example: the same guard, three projects

"LLM output must be schema-valid JSON; on violation, return the validation error verbatim; max 2 retries; then fail loudly" — appears in ingest (outline pass), flashcards (card generation), and Kaggle (experiment specs). It's ~20 lines of harness code and it converts an unreliable text generator into a function with a type signature. That one pattern is most of the variance reduction.
