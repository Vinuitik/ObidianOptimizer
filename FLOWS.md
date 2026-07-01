# ObsidianOptimizer — Documentation Index

Spring Boot API + React frontend for browsing and reviewing an Obsidian vault.

---

## Find all project docs

```powershell
Get-ChildItem -Path . -Recurse -Filter "*.md" | Where-Object { $_.FullName -notlike "*node_modules*" -and $_.FullName -notlike "*target*" } | Select-Object -ExpandProperty FullName
```

---

## Docs

| File | Type | Covers |
|---|---|---|
| [Backend FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/FLOWS.md) | Flows | REST endpoints, auth, file CRUD, diff-patch, soft-delete, cache |
| [Frontend FLOWS](frontend/FLOWS.md) | Flows | User interactions, routing, CRUD, auth, wiki-link resolution |
| [Frontend DESIGN](frontend/DESIGN.md) | Reference | Design tokens, atoms/molecules, state shape, markdown rendering |
| [Host Wrapper FLOWS](host-wrapper/FLOWS.md) | Flows | Image processing service, Anthropic vision API |
| [ML Architecture](architecture_plans/ML_ARCH.md) | Architecture | MCP server (Python, /mcp — implemented), pgvector search, VLM image pipeline |
| [ML FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/ml/FLOWS.md) | Flows | Hybrid search, embedding pipeline, MCP tools (embedder/mcp_server.py) |
| [Orchestration FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/ml/FLOWS_orchestration.md) | Flows | Worker lanes, scheduler pool, GPU single-slot arbiter, LLM router |
| [Sync FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/sync/FLOWS.md) | Flows | Per-file AES-256-GCM Google Drive sync, queue, device identity |
| [Cards FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/cards/FLOWS.md) | Flows | Flashcard agent: hash-diff worker, CLI generation, solver sandbox |
| [Flashcards Architecture](architecture_plans/FLASHCARDS_ARCH.md) | Architecture | Card types, FSRS-on-notes + bandit (Option A), assignments [PARTIALLY IMPLEMENTED] |
| [Backend FLOWS — Chrono](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/FLOWS.md#chrono-service) | Flows | Daily jobs: FileMover, Bankruptcy (chronic-neglect + mass lapse), SpreadCheck |
| [Ingest FLOWS](embedder/ingest/FLOWS.md) | Flows | Resource→notes stage 1: A/V whisper transcription, job API |
| [Ingest Architecture](architecture_plans/INGEST_AGENT_ARCH.md) | Architecture | Full pipeline plan + v1 execution stages, note splitter |
| [Link Sniffer Architecture](architecture_plans/LINK_SNIFFER_AGENT_ARCH.md) | Architecture | Extension-connected capture agent [NOT IMPLEMENTED] |
| [Mobile Architecture](architecture_plans/MOBILE_ARCH.md) | Architecture | Offline-first mobile app, sync engine [NOT IMPLEMENTED] |
| [Backend Testing](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/FLOWS.md#testing) | Testing | Unit tests (7 service layers), MockMvc controller tests, Testcontainers IT |
| [Frontend Testing](frontend/FLOWS.md#testing) | Testing | Vitest: useStore, TabBar, FolderTree filter, diff, LoginModal, NewNoteForm |
| [CI Pipeline](.github/workflows/ci.yml) | CI/CD | GitHub Actions: backend-unit, backend-integration, frontend, docker-build |
| [Deploy/Boot FLOWS](linux_scripts/FLOWS.md) | Flows | systemd boot service, restart-on-crash, log querying, host-wrapper respawn |
