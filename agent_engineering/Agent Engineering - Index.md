# Agent Engineering — Index

Notes on designing LLM agents that actually work. Written against three real designs: the ObsidianOptimizer ingest agent, the flashcards agent, and the Kaggle optimizer agent.

Reading order:

1. [[Workflows vs Agents]] — when you need an agent loop at all (usually: you don't)
2. [[Harness Engineering]] — the answer to "wtf is a harness"
3. [[The Determinism Boundary]] — the single most useful design move
4. [[Tool Design for Agents]] — tools are prompts, design them like prompts
5. [[Context Engineering]] — the model's RAM is small and expensive
6. [[Evals for Agents]] — if you can't measure it, you're decorating
7. [[Budgets and Failure Modes]] — agents fail in loops, cap everything
8. [[Sandboxing LLM and User Code]] — running untrusted code without fear
9. [[Things You Don't Know You Don't Know]] — the grab-bag of expensive surprises

One sentence per note, if you only keep one: **the model proposes, the harness disposes** — every property you want guaranteed (correctness, cost, safety, reproducibility) must live in deterministic code, because the model only ever offers probabilities.
