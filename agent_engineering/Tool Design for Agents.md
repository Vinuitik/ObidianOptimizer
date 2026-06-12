# Tool Design for Agents

A tool definition is **prompt text the model reads to decide behavior**. Tool design is prompt engineering with a schema attached. The interface between agent and tools even has a name — ACI, agent–computer interface — by analogy to HCI, and it deserves the same design effort.

## Rules that pay rent

1. **Few, orthogonal tools beat many overlapping ones.** Every extra tool is context cost + a wrong-choice opportunity. If two tools could plausibly handle the same request, the model will sometimes pick the worse one — merge them or sharpen the boundary.
2. **Name and describe for the model, not for your codebase.** `search_notes(query)` with "Hybrid semantic+keyword search over the vault. Returns top chunks with paths." beats `query_rrf_v2`. Put usage guidance, constraints, and *examples of when NOT to use it* in the description.
3. **Consolidate multi-step operations.** If the model always calls A then B then C, ship one tool that does A→B→C. Each round-trip is latency, tokens, and a failure opportunity.
4. **Errors are prompts.** A tool returning `"error: ENOENT"` teaches nothing. Return `"file not found: 'notes/ml.md' — did you mean 'notes/ML basics.md'? Use list_folder first."` The model course-corrects exactly as well as your error messages allow.
5. **Return token-lean results.** Tools that dump raw HTML/whole files flood the context ([[Context Engineering]]). Return the distilled thing: top-k chunks, not documents; ids + summaries, not full rows. Add a `detail` parameter rather than defaulting to verbose.
6. **Make tools forgiving on input** (accept both `path` styles, trim whitespace, coerce obvious types) **and strict on output** (always the same JSON shape, including the empty case — an empty list, never a missing key).
7. **Idempotent where possible; clearly destructive where not.** Agents retry. A `create_note` that double-fires should dedupe or fail loudly — never silently create twins. Anything irreversible gets a confirmation parameter or stays out of the toolset entirely (the vault MCP exposes no delete tool — the UI owns deletion, on purpose).
8. **Test tools with the model, not just unit tests.** Watch transcripts: which tools get confused for each other, where the model passes garbage, which descriptions it ignores. Then fix the *descriptions* — cheapest fix in the stack.

## Restriction is a feature

A smaller, sharper toolset is a form of [[Harness Engineering]] guard: the flashcards judge can only judge, the Kaggle executor refuses specs touching test folds. What the agent *cannot call* is part of the safety story — capability boundaries beat behavioral instructions every time.
