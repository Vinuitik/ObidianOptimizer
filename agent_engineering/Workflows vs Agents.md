# Workflows vs Agents

The most cited industry guidance (Anthropic's "Building Effective Agents") draws one line:

- **Workflow** — LLM calls orchestrated through *predefined* code paths. You know the steps; the LLM fills in steps that need judgment.
- **Agent** — the LLM *dynamically decides* its own steps and tool use in a loop. You don't know the path in advance.

**Always start with a workflow.** Agents trade latency, cost, and variance for flexibility — pay that price only when the path is genuinely unknowable upfront.

## The standard workflow patterns (cheapest first)

| Pattern | Shape | Use when |
|---|---|---|
| Prompt chaining | call A → validate → call B | task decomposes into fixed stages |
| Routing | classifier picks one of N handlers | distinct input categories (file types!) |
| Parallelization | N calls at once, aggregate | independent subtasks, or voting for confidence |
| Orchestrator–workers | LLM splits task, workers execute, LLM merges | subtasks unpredictable in number, not in kind |
| Evaluator–optimizer | generator ↔ critic loop, capped | clear eval criteria, iteration helps |

## Worked examples from my own projects

- **Ingest agent**: routing + prompt chaining. Path is fully known (extract → bundle → outline → write). Zero agent loop; the only "loop" is retry-on-schema-failure ×2.
- **Flashcards agent**: prompt chaining + evaluator (the blind self-check pass is a one-shot evaluator–optimizer).
- **Kaggle agent**: a real agent loop — *which experiment to run next* is unknowable upfront. But even there the loop only emits JSON specs; execution stays a workflow. See [[The Determinism Boundary]].

## Smell test

If you can draw the flowchart, it's a workflow — build it as one. If your flowchart has a box labeled "model figures it out," that box is the agent; make it as small as possible and put a budget on it ([[Budgets and Failure Modes]]).
