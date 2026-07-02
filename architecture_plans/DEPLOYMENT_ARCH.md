# Deployment & Shipping Architecture [NOT IMPLEMENTED — and STALE in parts]

> ⚠ 2026-07-02: the "ship to friends" installer remains future work, but several
> assumptions below are dead: there is **no Ollama** (embeddings run as pure ONNX inside
> the embedder container), **no VideoManager** (yt-dlp was salvaged into
> `embedder/download/`), and no `nomic-embed-text`. The stack that would actually be
> packaged is the current `docker-compose.yml` (backend, frontend/nginx, postgres+pgvector,
> embedder, cloudflared) + the optional host-wrapper. The *actual* running deployment
> (systemd boot, tunnel-only exposure) is documented in `linux_scripts/FLOWS.md` and
> `frontend/FLOWS.md` — this file is only about a future distributable. Rewrite against
> the real compose file before acting on it.

## Goal
To ship Obsidian Optimizer to friends as a single, self-contained deliverable that runs purely locally. They should be able to double-click and run the entire suite without configuring environments, installing Python, or setting up PostgreSQL.

## The "All-in-One" Docker Compose Strategy

Since the stack has multiple moving parts (Spring Boot API, React frontend, PostgreSQL+pgvector, Ollama, Python Host Wrapper, and the Python VideoManager), the only frictionless way to distribute this to a non-technical end-user is via Docker Compose.

### What the user must have:
*   Docker Desktop installed.
*   An Anthropic API key (for the host wrapper's Claude vision capabilities).

### The Deliverable Payload (The Executable Installer)
Instead of handing users a raw `.zip` and asking them to run terminal scripts, we will package the application into a standard Windows Installer (`.exe` or `.msi`) with a custom logo.

1.  **The Installer (Inno Setup / NSIS)**
    *   We use a deployment tool like Inno Setup (which is free and standard) to compile all our files (`docker-compose.yml`, shell scripts, frontend static assets) into a single `ObsidianOptimizer_Install.exe`.
    *   The installer checks if Docker Desktop is installed. If not, it can prompt or link the user to download it.
    *   It drops a branded shortcut on the user's Desktop with your custom Obsidian Optimizer logo.

2.  **The Desktop Launcher App (Tauri or Electron)**
    *   When the user double-clicks the desktop shortcut, they aren't thrown into a scary command prompt.
    *   Instead, a lightweight native window opens (built with Tauri or Electron).
    *   **First Run Setup**: This UI asks them politely for their `Vault Path` and their `Anthropic API Key` and saves it to a `.env` file behind the scenes.
    *   **Orchestration**: The launcher app has a "Start Optimizer" button. When clicked, the app runs `docker-compose up -d` internally, tracks the progress, pulls the Ollama models silently, and finally opens the main UI inside the local app window (or system browser).

This approach transforms a "hacker script" into a legitimate, professional software product that anyone knows how to use—you just double-click the icon.

### Packaging Details

1.  **React Frontend & Nginx**
    *   The Vite build runs during the Docker image build process or beforehand.
    *   The static files are served by a lightweight `nginx:alpine` container.

2.  **Spring Boot Backend**
    *   Packaged using the `maven:eclipse-temurin` image. We map their local vault directory (defined in `.env`) as a Docker volume so the backend can read/write their physical markdown files.

3.  **Database (PostgreSQL + pg_search/BM25)**
    *   The custom database image we planned (with `pgvector` and robust text search) will run in the background. A mapped volume ensures their vector data persists across restarts.

4.  **Ollama (Local Embeddings & Whisper)**
    *   Runs in a container.
    *   The `start` script will execute a health-check wait, then automatically run `docker exec ollama pull nomic-embed-text` so the user doesn't have to manually fetch models.

5.  **Python Host Wrapper (The tricky part)**
    *   *Problem*: Originally, this was designed to run on the OS host to easily read local file paths and maintain a separate environment.
    *   *Docker Solution*: We package this into a Python Docker image anyway. By using Docker volumes, it can still read the user's vault contents. It connects to the shared Docker network to communicate with Spring Boot, keeping the entire ecosystem locked within Compose.

6.  **Sister App (VideoManager)**
    *   We add the VideoManager` as another service block inside the overarching `docker-compose.yml`.
    *   It gets its own Python FastAPI container and maps to the local folders so downloading and audio conversion happen seamlessly beside the main app.

## Launch Sequence (The User Experience)
1. User downloads `ObsidianOptimizer_Setup.exe` and double-clicks it.
2. An installation wizard guides them through setup and drops a branded icon on their desktop.
3. User double-clicks the Desktop Icon.
4. A sleek, minimal window opens prompting them for their `Vault Path` and optional `Anthropic API Key`.
5. Under the hood, the launcher runs `docker-compose up -d`, waits for Ollama to become healthy, and pulls the `nomic-embed-text` model.
6. The window transitions seamlessly into the Obsidian Optimizer application (loading the React frontend).

This approach guarantees a polished "it works on my machine" experience that looks and feels exactly like commercial software.