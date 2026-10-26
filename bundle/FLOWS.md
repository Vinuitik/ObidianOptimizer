# Self-host user bundle

Files: compose.yml, compose.gpu.yml, .env.template, manifest.template.json, ../host-wrapper/Dockerfile, ../embedder/Dockerfile, ../.github/workflows/release.yml, ../*/.dockerignore

## Release flow
push tag `vX.Y.Z` → `release.yml` job `images` (matrix: backend, frontend, llm, embedder CPU, embedder `-gpu`) → buildx push `ghcr.io/vinuitik/obsidian-optimizer-<svc>:<X.Y.Z>` + `:latest` → job `release` → `sed @VERSION@` over `manifest.template.json` → `manifest.json` → GitHub Release assets: manifest.json, compose.yml, compose.gpu.yml, .env.template.
To change image list/platforms: `release.yml` matrix + `manifest.template.json` `images`.
To change min launcher version: `manifest.template.json` `minLauncher`.

## Runtime flow (launcher-driven)
Launcher writes `.env` from `.env.template` → `docker compose -p obsopt -f compose.yml [-f compose.gpu.yml] --env-file .env up -d` → postgres + rabbitmq healthy → llm starts → embedder healthy (first start downloads model, `start_period` 600s) → backend → frontend on `127.0.0.1:${OO_LOCAL_PORT}` → cloudflared tunnel → `<name>.obsidianoptimizer.uk`.
Backend/embedder reach the LLM router at `WRAPPER_URL=http://llm:5001` (hard-wired in compose, not from `.env`).
To change local port: `OO_LOCAL_PORT`. To add GPU: launcher adds `-f compose.gpu.yml` (swaps embedder to `:<ver>-gpu`, reserves nvidia device).

## claude-cli disabled
`host-wrapper/Dockerfile` ENV `LLM_TEXT_PRIORITY`/`LLM_VISION_PRIORITY` omit `claude-cli`; `compose.yml` `llm.environment` re-pins them; claude binary is not in the image. `llm_router.Router._chain()` only walks the priority lists.
To change: `llm_router.TEXT_PRIORITY` env in `compose.yml` service `llm`.

## Embedder variants
`embedder/Dockerfile` build-args: `BASE_IMAGE` + `GPU`. Default (GPU=1, CUDA base) = owner's build, unchanged. User CPU image = `BASE_IMAGE=ubuntu:22.04 GPU=0` (drops `onnxruntime-gpu`, `nvidia-cublas-cu12`; faster-whisper's CPU onnxruntime is used). GPU image published as tag `<ver>-gpu` (amd64 only).

## Secrets
No `.env` in any image: `.dockerignore` in backend, frontend, embedder, host-wrapper excludes `.env*`. Secrets enter only via compose `environment` at runtime (nginx envsubst injects `MCP_API_TOKEN` at container start).

## Technology Notes
- **Apple Silicon (linux/arm64, Docker Desktop, CPU only)**: all images are built multi-arch except `embedder:*-gpu`. Verified aarch64 wheels exist for onnxruntime, ctranslate2, pymupdf, opencv-python-headless, psycopg-binary, tokenizers, numpy, Pillow, lxml, av; no torch in embedder. Base images verified arm64: paradedb/paradedb (`docker manifest inspect`: amd64+arm64), cloudflared, ubuntu:22.04, eclipse-temurin:21-jre-alpine, nginx:alpine, rabbitmq (imagetools). arm64 CI builds run under QEMU: slow (embedder pip install maybe 20-40 min) — consider native arm runners if it hurts. The arm64 embedder build itself was NOT run locally.
- **paradedb/paradedb:latest and cloudflared:latest are unpinned**: a Postgres major bump on `latest` breaks an existing data volume, and backend Dockerfile pins `postgresql18-client`. Pin a tag before real users. [NOT IMPLEMENTED]
- **Memory (Docker Desktop VM default = half host RAM, e.g. 8GB on a 16GB Mac; tell users to give it >=6GB)**. Estimated steady state: postgres ~0.5GB, rabbitmq ~0.15GB, backend JVM ~0.7-1.2GB (`MaxRAMPercentage=35`), embedder ~1.5GB idle (embeddinggemma int8) up to ~2.5-3GB while transcribing with whisper `small`, llm ~0.1GB, frontend ~0.02GB, cloudflared ~0.05GB. Total ~3.5GB idle, ~5GB peak. `distil-large-v3` adds ~4GB — not the default here (`WHISPER_MODEL=small`).
- Memory/CPU defaults vs owner stack: `MaxRAMPercentage` 60→35, `EMBED_BATCH_SIZE` 16→8, `EMBEDDING_BATCH_LIMIT` 8→4, `IMAGE_WORKER_PARALLELISM` 4→2, `IMAGE_BATCH_SIZE` 4→2, embedder cpus 3→2 (`OO_EMBEDDER_CPUS`). Slower backlog drain, less OOM-kill risk. A Docker VM OOM-kill shows as a container restarting (`restart: unless-stopped`).
- **CPU embedder speed**: whisper on CPU is slow (minutes per video-minute on `small`); ingest of long A/V is the pain point.
- **Vault bind mounts**: `_reports`, `_workspace`, `resources` must pre-exist on the host or Docker creates them root-owned (breaks writes on Linux). On Docker Desktop macOS the vault path must be in a shared directory (home is by default); bind I/O via virtiofs is slower than a named volume.
- **llm container**: `VAULT_HOST_PATH=/vault` and vault mounted `:ro` at `/vault`, so backend's `/vault/...` image paths resolve to themselves. Router state (cooldowns) is in-memory; resets on restart. The `/vaults` registration endpoint shells out to `docker compose` and does NOT work inside the container (no docker, no repo) — multi-vault is unsupported in the bundle [NOT IMPLEMENTED].
- **Hardcoded internal creds**: RabbitMQ `guest/guest` — safe only because no host port is published. Postgres/rabbit/embedder/backend ports are not published; only frontend on 127.0.0.1.
- **cloudflared** requires `CLOUDFLARE_TUNNEL_TOKEN` (compose refuses to render without it); every user needs the registry claim before first `up`.
- `latest` tags are pushed too, but compose always uses `${OO_VERSION}` so updates are explicit.
- Downloads (`ext-dist`, `downloads`) mounts deliberately dropped: `/ext/` and `/download/` nginx routes 404 in the bundle.

## Change Index
| Thing | Where |
|---|---|
| Image tags / version | `OO_VERSION` in `.env`; `release.yml` `Version from tag` step |
| Add/remove a published image | `release.yml` matrix + `manifest.template.json` |
| Platforms | `release.yml` matrix `platforms` |
| Local port | `OO_LOCAL_PORT` → `compose.yml` frontend `ports` |
| Memory knobs | `compose.yml` backend `JAVA_TOOL_OPTIONS`, embedder `EMBED_BATCH_SIZE`, `WHISPER_MODEL`, `OO_EMBEDDER_CPUS` |
| LLM providers / claude-cli | `compose.yml` llm env, `host-wrapper/Dockerfile` ENV |
| GPU support | `compose.gpu.yml`; `embedder/Dockerfile` `GPU` arg |
| Secrets exclusion | `*/.dockerignore` |
| Required env vars | `.env.template` (`:?` guards in `compose.yml`) |
| Manifest schema | `manifest.template.json` |
