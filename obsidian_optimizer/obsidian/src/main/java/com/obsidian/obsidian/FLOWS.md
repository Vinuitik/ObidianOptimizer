# Backend Flows — Navigation Index

Subsystem docs live next to the code they describe.

| Domain | FLOWS.md | Key classes |
|---|---|---|
| **notes** | [notes/FLOWS.md](notes/FLOWS.md) | NotesController, FileRepository, NoteIndexRepository, NoteLinkRepository, FrontmatterParser |
| **settings** | [settings/FLOWS.md](settings/FLOWS.md) | SettingsController, SettingsRepository |
| **chrono** | [chrono/FLOWS.md](chrono/FLOWS.md) | ChronoService, ChronoController, FileMoverService, FileCheckerService, BankruptcyService, SpreadService, FrontmatterRewriter |
| **media** | [media/FLOWS.md](media/FLOWS.md) | MediaController |
| **config** | [config/FLOWS.md](config/FLOWS.md) | SecurityConfig, WebConfig, ObsidianApplication |
| **ml** | [ml/FLOWS.md](ml/FLOWS.md) | McpController, SearchService, MarkdownPreprocessor — Phase 1/2 stubs |

## Cross-Package Dependencies

```
settings ← notes (FileRepository reads SettingsRepository.getVaultPath)
settings ← chrono (ChronoService reads maxDailyReviews, bankruptcyLimit)
settings ← media (MediaController reads SettingsRepository.getVaultPath)
notes    ← settings (FileRepository uses SettingsRepository)
notes    ← chrono (ChronoService calls FileRepository.listMdPaths, triggerDeltaSync)
```

## Infrastructure

`docker-compose.yml` — three services. Start: `.\start.ps1`.

| Service | Port | Notes |
|---|---|---|
| Java backend | 8082 | WAR, self-executable jar |
| Postgres + pgvector | 5432 | `pgvector/pgvector:pg16`, data in `postgres_data` volume |
| Frontend (Nginx) | 8083 | `/api/*` proxied to backend |

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
