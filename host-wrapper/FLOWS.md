# Host Wrapper Service — the LLM Gateway

Files: main.py, llm_router.py, requirements.txt, .env.example, start.bat

Runs on Windows host (NOT in Docker). Spring Boot + embedder containers call it via
`host.docker.internal:5001`. **This is the single place the app talks to any LLM** —
anything that needs a model (vision extraction, flashcard generation, future agents)
goes through here so provider keys, fallback, and rate limiting live in one process.

---

## Startup

`start.bat` → `pip install -r requirements.txt` → `python main.py` → Flask on `0.0.0.0:5001` (threaded)
Config: **repo-root `.env` is the single source of truth** — main.py loads `../.env`
first, then an optional local `host-wrapper/.env` as override (local wins).
To change port: root `.env → PORT` (or local override)

---

## LLM Router (llm_router.py)

One `Router` instance routes every request. Free tiers first, Claude dead last.

```
Vision chain (LLM_VISION_PRIORITY): gemini → github → mistral → groq → anthropic
Text chain   (LLM_TEXT_PRIORITY):   groq → github → mistral → deepseek → gemini → claude-cli
```

Gemini is deliberately LATE in the text chain — its 1,500 req/day quota is reserved
for the image backlog. Claude (API or CLI subscription credits) is only reached when
every free provider is exhausted, so coding quota is never burned on bulk work.

**Sharding, not racing:** each provider has `in_flight ≤ 1`, so concurrent requests
lease different providers (image A → Gemini while image B → Groq). Same total
coverage, parallel throughput. A provider is also rate-spaced (`min_interval` ≈
60/free-tier-RPM between request starts).

**Failover:** `Router._run()` → `_acquire()` leases the highest-priority free
provider (blocks up to `LLM_ACQUIRE_DEADLINE_S`, default 150s) → on 429 the provider
is benched (`Retry-After` honored, else 30s·2^failures, cap 1h) and the request
retries on the next provider → all providers failed ⇒ `RouterError` ⇒ HTTP 503
(Java marks the job SKIPPED and retries next cycle).

Providers without a key in `.env` are skipped silently. `claude-cli` needs no key.

| Provider | Key (root .env) | Default model | Vision | Free limits |
|---|---|---|---|---|
| gemini | `GEMINI_API_KEY` | gemini-2.5-flash | ✓ | 15 RPM, 1500/day |
| github | `GITHUB_MODELS_TOKEN` | openai/gpt-4o-mini | ✓ | ~15 RPM + daily cap |
| mistral | `MISTRAL_API_KEY` | mistral-small-latest | ✓ | ~1 req/s |
| groq | `GROQ_API_KEY` | llama-3.3-70b / llama-4-scout (vision) | ✓ | ~30 RPM |
| deepseek | `DEEPSEEK_API_KEY` | deepseek-chat | ✗ | paid, ~pennies |
| anthropic | `ANTHROPIC_API_KEY` | claude-haiku-4-5 | ✓ | paid API |
| claude-cli | (CLI auth) | `SYNTH_MODEL` (haiku) | ✗ | subscription credits |

All providers except anthropic/claude-cli speak the **OpenAI chat-completions
format** (Gemini via its `/v1beta/openai/` compatibility endpoint) — one HTTP code
path, images as `data:` URI `image_url` parts.

To change chains: `.env → LLM_VISION_PRIORITY / LLM_TEXT_PRIORITY`
To change a model: `.env → GEMINI_MODEL / GROQ_VISION_MODEL / ...` (see .env.example)
To add a provider: `llm_router._build_providers()` + add name into a priority chain

---

## GET /health

Returns `{"status": "ok"}` — Spring Boot calls this on startup to check wrapper availability.
If unreachable: embedding pipeline skips image processing, indexes text-only (graceful degradation).

## GET /providers

Router introspection for debugging/dashboard:
`{"gemini": {"configured": true, "in_flight": 0, "cooldown_s": 0, "ok": 12, "failed": 1}, ...}`

---

## POST /process-image

Request: `{"image_path": "/vault/folder/image.png"}` → Response: `{"text": str, "provider": str}`
`/vault` prefix → translated to `VAULT_HOST_PATH`/`HOST_VAULT_PATH` (vault is shared volume)

`main.process_image()`:
→ resolve host path → check exists (404 if not)
→ detect MIME from extension
→ `router.complete_vision(IMAGE_PROMPT, bytes, mime)` — vision chain above
→ `{"text": ..., "provider": "gemini"}` | RouterError → 503

Prompt: extracts raw text from screenshots; describes structure/relationships from diagrams.
Called concurrently by `ImageProcessingWorker` — concurrency is what makes sharding work.

---

## POST /complete

Request: `{"prompt": str, "system"?: str, "model"?: str}` → Response: `{"text": str, "provider": str}`

Routed through the TEXT chain — free providers first. The `model` field only
applies if the `claude-cli` provider is reached (headless `claude -p`, bills the
Claude **subscription's included credits, NOT API credits**). Used by the flashcard
generation agent (`embedder/flashcards/generate.py`).

`claude-cli` specifics: resolves `claude.cmd` via `shutil.which`, prompt on stdin,
`--output-format json`, timeout `CLI_TIMEOUT_S` (default 180s).

---

## Technology Notes

- **Not in Docker by design** — needs the host `claude` CLI auth context; also lets
  one process own all outbound LLM rate limiting regardless of how many containers call it.
- **Root .env as SSOT** — `load_dotenv(../.env)` then `load_dotenv(.env, override=True)`.
  Renaming/moving the repo folder breaks the relative path only if main.py moves
  relative to the root .env.
- **In-memory router state** — cooldowns, ok/fail counters, and rate spacing reset on
  wrapper restart. A provider that was benched for the day will be re-tried once after
  a restart (it just 429s again and re-benches). No persistence by design.
- **`in_flight ≤ 1` per provider** — throughput ceiling is (number of configured
  providers) concurrent requests. The Java worker's thread pool should be sized ≈ the
  number of configured vision providers.
- **Flask threaded mode** — required; without it concurrent requests serialize and
  sharding degenerates to pure sequential fallback.
- **OpenAI-compat quirks** — Gemini ignores some OpenAI params; `max_tokens` is
  accepted by all current providers. If a provider 400s on an unknown param, it gets
  benched and the chain continues (visible in `/providers` fail count).
- **Windows path normalization** — `VAULT_HOST_PATH`/`HOST_VAULT_PATH` backslashes
  normalized to forward slashes on load.
- **Graceful degradation** — wrapper down ⇒ Spring Boot skips image processing;
  notes still indexed/searchable on text content.
- **PyInstaller packaging** — `pyinstaller --onefile main.py` still works; friends
  need their own keys in a root-style .env next to the exe's parent dir.

---

## Change Index

| Thing to change | Where |
|---|---|
| Any API key | root `.env` → `GEMINI_API_KEY`, `GITHUB_MODELS_TOKEN`, `MISTRAL_API_KEY`, `GROQ_API_KEY`, `DEEPSEEK_API_KEY`, `ANTHROPIC_API_KEY` |
| Provider order | root `.env` → `LLM_VISION_PRIORITY`, `LLM_TEXT_PRIORITY` |
| Models | root `.env` → `GEMINI_MODEL`, `GITHUB_MODELS_MODEL`, `MISTRAL_MODEL`, `GROQ_TEXT_MODEL`, `GROQ_VISION_MODEL`, `DEEPSEEK_MODEL`, `ANTHROPIC_MODEL`, `SYNTH_MODEL` |
| Vault path (host side) | root `.env → HOST_VAULT_PATH` (or `VAULT_HOST_PATH` override) |
| Port | `.env → PORT` |
| Extraction prompt | `main.py → IMAGE_PROMPT` |
| Rate spacing / RPM | `llm_router._build_providers()` → `min_interval` |
| Cooldown policy | `llm_router.Provider.bench()` |
| Acquire deadline | `.env → LLM_ACQUIRE_DEADLINE_S` (default 150s) |
| Request timeout | `.env → LLM_REQUEST_TIMEOUT_S` (default 120s) |
| Max output tokens | `.env → LLM_TEXT_MAX_TOKENS` / `LLM_VISION_MAX_TOKENS` |
| CLI timeout | `.env → CLI_TIMEOUT_S` (default 180s) |
| Add a provider | `llm_router._build_providers()` + priority chain |
