# Flashcard Generation Flows — note → cards

Files: generate.py, validate.py, solver_sandbox.py, judge.py
Architecture: architecture_plans/FLASHCARDS_ARCH.md
Image provenance + the debug-trace write endpoint: obsidian_optimizer/.../ml/FLOWS.md

LLM goes through host-wrapper `/complete` (free providers first, claude-cli last) —
same router as ingest. `SYNTH_MODEL` only applies if the CLI is reached.

---

## generate_for_note(note_path, content, source_hash)

```
_fetch_image_rows(note_path)        ← note_chunks WHERE source='image' (text, image_path, provider)
_format_with_descriptions(content, [r.text]) → assembled input (note + "## Image contents" block)
GEN_PROMPT.format(...)              → the prompt the model actually sees
loop ≤ 1+MAX_RETRIES:
  _complete(prompt[+error feedback]) → raw
  _extract_json(raw)               → cards   (parse failure → retry with error)
  validate.validate_cards(cards)   → (valid, errors); deterministic schema + solver checks
  break when valid and not errors  (or keep survivors at the last attempt)
_self_check(valid)                 → PASS 2: model re-answers its own mcq/exercise BLIND; drops mismatches
_store(note_path, source_hash, …)  → upsert into postgres `cards` (note_path+card_hash)
_write_trace(...)                  → ALWAYS, on every exit path (best-effort)
```

To change card counts/mix: `N_MCQ / N_OPEN / N_EX`
To change the generation prompt: `GEN_PROMPT`  ·  self-check prompt: `CHECK_PROMPT`
To change retry budget: `MAX_RETRIES`

## Debug trace — "what did the note look like to the agent?"

Written on EVERY run (success or total failure) so you can locate where quality
broke down: bad prompt, thin note, or — the common culprit — bad upstream image
transcription that the agent faithfully made cards about.

```
_build_trace_md(...) sections:
  Images as transcribed   → per image: source file + provider + the VERBATIM text
                            (image_path/provider come from note_chunks provenance)
  Assembled input         → the exact text fed inside GEN_PROMPT
  Generation attempts     → each raw LLM reply + parse/validation errors
  Outcome                 → self-check drops, stored, rejected
_write_trace → POST {BACKEND_URL}/api/internal/debug-trace
  {relPath: "flashcards/<slug>.md", content: md}, header X-Internal-Token=MCP_API_TOKEN
  → Java writes vault/_debug/flashcards/<slug>.md DIRECTLY (no indexing/embedding/cards)
```

The embedder mounts the vault **read-only** (`/vault:ro`), so it cannot write the
file itself — it hands the markdown to the Java backend, which owns the writable
mount. `_debug` is in `FileRepository.EXCLUDED_DIRS`, so traces are never scanned,
embedded, ingested, or turned into cards.

To change where traces land: `relPath` in `_write_trace` (confined under `_debug/`)
To disable tracing: unset `MCP_API_TOKEN` (the write is fail-closed → no-op)
To change what the trace shows: `_build_trace_md`

---

## Technology Notes

- **Trace is a side-effect, never load-bearing.** `_write_trace` swallows every
  error and is skipped entirely when `MCP_API_TOKEN` is unset (unit tests). A bad
  trace write can never fail or slow card generation. The trade-off: if the backend
  is down at generation time, that run's trace is silently lost — there is no queue
  or retry for traces (unlike the image/ingest pipelines).
- **Traces overwrite, not append.** One file per note slug, last run wins
  (`Files.writeString`, no timestamp in the name). You see the MOST RECENT
  generation only — not history. If two notes slugify to the same name they collide.
- **Provenance depends on the image worker having run with the new columns.** Rows
  written before the `image_path`/`provider` migration show `?` for source/provider
  (columns are NULL) until that image is re-processed.
- **Image descriptions are pulled at generation time, not pinned.** The trace shows
  what `note_chunks` held WHEN cards were generated; if the image worker later
  re-transcribes, the stored cards and the trace can drift from the live chunks.
- **`_self_check` is best-effort too** — if PASS 2 fails (wrapper error) all cards
  are KEPT, not dropped, and the trace records `self-check dropped: 0`. Absence of
  drops is not proof of correctness.

---

## Change Index

| Thing to change | Where |
|---|---|
| Card counts / difficulty mix | `generate.py → N_MCQ / N_OPEN / N_EX` |
| Generation prompt | `generate.py → GEN_PROMPT` |
| Blind self-check prompt | `generate.py → CHECK_PROMPT` |
| Retry budget | `generate.py → MAX_RETRIES` |
| Image-description fetch (provenance) | `generate.py → _fetch_image_rows` |
| Trace contents | `generate.py → _build_trace_md` |
| Trace write target / disable | `generate.py → _write_trace` (`relPath`, `MCP_API_TOKEN`) |
| Trace write endpoint (Java) | `InternalAgentController.writeDebugTrace` |
| Dirs excluded from scan (`_debug`) | `FileRepository.EXCLUDED_DIRS` |
| Card schema / solver sandbox rules | `validate.py`, `solver_sandbox.py` |
| Synthesis model (claude-cli only) | `SYNTH_MODEL` env |
| Backend URL / internal token | `BACKEND_URL` / `MCP_API_TOKEN` env |
