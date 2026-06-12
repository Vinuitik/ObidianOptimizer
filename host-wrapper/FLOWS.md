# Host Wrapper Service

Files: main.py, requirements.txt, .env, start.bat

Runs on Windows host (NOT in Docker). Spring Boot container calls it via `host.docker.internal:5001`.

---

## Startup

`start.bat` → `pip install -r requirements.txt` → `python main.py` → Flask on `0.0.0.0:5001`  
Config: copy `.env.example` → `.env`, fill `ANTHROPIC_API_KEY` + `VAULT_HOST_PATH`  
To change port: `.env → PORT`

---

## GET /health

Returns `{"status": "ok"}` — Spring Boot calls this on startup to check wrapper availability.  
If unreachable: embedding pipeline skips image processing, indexes text-only (graceful degradation).

---

## POST /process-image

Request: `{"image_path": "/vault/folder/image.png"}`  
`/vault` prefix → translated to `VAULT_HOST_PATH` (vault is shared volume, host can access same files)  

`main.process_image()`:  
→ resolve host path → check exists (404 if not)  
→ detect MIME from extension  
→ base64-encode image bytes  
→ `anthropic.messages.create(claude-haiku-4-5, image + prompt)`  
→ return `{"text": extracted_content}`

Prompt: extracts raw text from screenshots; describes structure/relationships from diagrams.  
Model: `claude-haiku-4-5-20251001` — cheapest Claude with vision, ~$0.25/MTok input.  
Called only for images in notes — infrequent, low cost.

**Requires:** `ANTHROPIC_API_KEY` from console.anthropic.com (separate from Claude.ai subscription).
[Migration option: route through `claude -p` like /complete below to use subscription credits instead.]

---

## POST /complete

Request: `{"prompt": str, "system"?: str, "model"?: str}` → Response: `{"text": str}`

Shells out to the **`claude` CLI** (headless `-p --output-format json`) — bills the
Claude **subscription's included credits, NOT API credits**. That's the whole point:
used by the flashcard generation agent (`embedder/flashcards/generate.py`).

`main.complete()`:
→ resolve CLI via `shutil.which("claude")` (npm shim is `claude.cmd` on Windows)
→ prompt on stdin, `--model` from request or `SYNTH_MODEL` env (default haiku)
→ parse stdout JSON → return `result` field
→ 504 on timeout (`CLI_TIMEOUT_S`, default 180s), 502 on nonzero exit

**Requires:** `claude` CLI installed and authenticated on the host — no API key.

---

## Technology Notes

- **Not in Docker by design** — needs access to host `claude` auth context and avoids adding a GPU-dependent container. Called over Docker's `host.docker.internal` bridge.
- **Windows path normalization** — `VAULT_HOST_PATH` backslashes are normalized to forward slashes on load. Pass paths with either separator in `.env`.
- **Graceful degradation** — if wrapper is down, Spring Boot skips image processing. Notes are still indexed and searchable on their text content.
- **PyInstaller packaging** — `pyinstaller --onefile main.py` produces a single `.exe` for distribution. Friends still need their own `ANTHROPIC_API_KEY`.

---

## Change Index

| Thing to change | Where |
|---|---|
| API key | `.env → ANTHROPIC_API_KEY` |
| Vault path (host side) | `.env → VAULT_HOST_PATH` |
| Port | `.env → PORT` |
| Vision model | `main.py → process_image()` model param |
| Extraction prompt | `main.py → IMAGE_PROMPT` |
| CLI completion model | request `model` field / `SYNTH_MODEL` env (default haiku) |
| CLI timeout | `CLI_TIMEOUT_S` env (default 180s) |
