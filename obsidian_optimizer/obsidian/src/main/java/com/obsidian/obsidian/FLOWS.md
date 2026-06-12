# Backend Flows — Navigation Index

Subsystem docs live next to the code they describe.

| Domain | FLOWS.md | Key classes |
|---|---|---|
| **notes** | [notes/FLOWS.md](notes/FLOWS.md) | NotesController, FileRepository, NoteIndexRepository, NoteLinkRepository, FrontmatterParser |
| **settings** | [settings/FLOWS.md](settings/FLOWS.md) | SettingsController, SettingsRepository |
| **chrono** | [chrono/FLOWS.md](chrono/FLOWS.md) | ChronoService, ChronoController, FileMoverService, FileCheckerService, BankruptcyService, SpreadService, FrontmatterRewriter |
| **media** | [media/FLOWS.md](media/FLOWS.md) | MediaController |
| **config** | [config/FLOWS.md](config/FLOWS.md) | SecurityConfig, WebConfig, ObsidianApplication |
| **ml** | [ml/FLOWS.md](ml/FLOWS.md) | SearchService, EmbeddingService, NoteEmbeddingWorker, ImageProcessingWorker, MarkdownPreprocessor |
| **sync** | [sync/FLOWS.md](sync/FLOWS.md) | Per-file AES-256-GCM encrypted Google Drive sync, sync_queue, device identity |
| **cards** | [cards/FLOWS.md](cards/FLOWS.md) | CardJobWorker, FsrsService, BanditService, ReviewService, AssignmentService |
| **stats** | StatsController.java (single file) | `GET /api/stats` — dashboard count aggregates + wrapper provider proxy |

## Cross-Package Dependencies

```
settings ← notes (FileRepository reads SettingsRepository.getVaultPath)
settings ← chrono (ChronoService reads maxDailyReviews, bankruptcyLimit)
settings ← media (MediaController reads SettingsRepository.getVaultPath)
notes    ← settings (FileRepository uses SettingsRepository)
notes    ← chrono (ChronoService calls FileRepository.listMdPaths, triggerDeltaSync)
```

## Infrastructure

`docker-compose.yml` — four services (+ optional tunnel). Start: `.\start.ps1` (generates self-signed certs into `./certs` on first run).

| Service | Host port | Notes |
|---|---|---|
| Frontend (Nginx TLS edge) | 8443 (https), 8083 (http→https redirect) | security headers, login rate limit, `/api/*` → backend; internal :8081 for tunnel ingress |
| Java backend | 127.0.0.1:8084 | loopback only — browsers go through nginx |
| Postgres | 127.0.0.1:5435 | loopback only; built from `./db` (paradedb: pgvector + pg_search), data in `postgres_data` volume |
| Embedder + MCP | 127.0.0.1:8000 | `/embed`, `/health`, and MCP at `/mcp` (X-API-Key) |
| cloudflared | — | opt-in: `docker compose --profile tunnel up`; public hostname → `http://frontend:8081` |

## Testing Quick Reference

```powershell
# Unit tests (no Docker)
mvn test -Dtest="!*IT" --no-transfer-progress

# Integration tests (Docker required)
mvn test -Dtest="*IT" --no-transfer-progress

# All tests
mvn test --no-transfer-progress
```

Test class locations mirror the main package tree: `src/test/java/com/obsidian/obsidian/{notes,settings,chrono,media}/`.
