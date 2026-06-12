# Context Engineering

Prompt engineering asks "how do I phrase this?" Context engineering asks **"what is in the model's window at this moment, and is every token earning its place?"** For agents — many turns, tool results piling up — the second question dominates.

## Why it matters

- Long contexts degrade attention (models reliably use the start and end of the window better than the middle — "lost in the middle").
- Tokens are money, and in a loop you pay for the same history every iteration.
- Irrelevant context isn't neutral — it actively distracts; models imitate and latch onto stale errors and dead ends left in the transcript.

## Patterns

1. **Rebuild, don't accumulate.** Best agents don't carry a growing chat transcript; they *reconstruct* the prompt each turn from durable state. Kaggle agent: prompt = plan + profile + ledger table, every iteration, stateless. Crash-proof, debuggable, and the prompt can't rot.
2. **Just-in-time retrieval.** Don't preload everything the agent *might* need; give it search tools and let it pull what it *does* need. (This is the design rationale of agentic search over RAG-dump-everything.)
3. **Compaction.** When history must persist past the window, summarize old turns into a structured digest — decisions made, files touched, open questions — and drop the raw turns. Lossy, so compact *outcomes*, never delete *constraints*.
4. **Sub-agents as context firewalls.** A subtask that needs to read 50 files shouldn't dump 50 files into the parent's window. Spawn a worker whose context dies with it; only its conclusion returns. Context isolation, not parallelism, is the main reason multi-agent setups exist.
5. **Structure beats prose.** A JSON bundle or a compact table is denser and more reliably parsed than narrative. The ingest agent's Extraction Bundle is context engineering as much as it is a contract ([[The Determinism Boundary]]).
6. **Mind the KV-cache.** Providers cache prompt prefixes; an append-only prompt with a stable prefix can be ~10× cheaper and much faster than one that rewrites earlier content each turn. Keep the system prompt and tool definitions byte-stable; append new state at the end. A timestamp in the system prompt destroys the cache every call.
7. **Token-lean tools.** Most context bloat enters through tool results — fix it at the source ([[Tool Design for Agents]]).

## The test

Open any single model call your agent makes and read the full prompt. For every block ask: *if I deleted this, would the output get worse?* If you can't answer yes, delete it. Agents accumulate context like attics accumulate boxes.
