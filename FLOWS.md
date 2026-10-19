# ObsidianOptimizer — Documentation Index

Spring Boot API + React frontend for browsing and reviewing an Obsidian vault.

---

## Find all project docs

```bash
find . -name 'FLOWS*.md' -not -path '*/node_modules/*' -not -path '*/target/*' -not -path './.claude/*'
```
Naming: `FLOWS.md` = a folder's main flows; `FLOWS_<topic>.md` = a focused sub-flow in the same folder (e.g. `ml/FLOWS_orchestration.md`, `tracks/FLOWS_feed.md`).

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
| [Ingest Architecture](architecture_plans/INGEST_AGENT_ARCH.md) | Architecture | Full pipeline plan + v1 execution stages, note splitter [IMPLEMENTED] |
| [Download FLOWS](embedder/download/FLOWS.md) | Flows | yt-dlp captions + offline media downloads (salvaged VideoManager core) |
| [Inbox FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/inbox/FLOWS.md) | Flows | Learn Inbox triage: `_inbox/` staging, file/discard, review exclusion |
| [Capture Architecture](architecture_plans/CAPTURE_ARCH.md) | Architecture | Capture model: resource → ordered proposed notes → Learn queue (phases 1–2 shipped; see STATUS table) |
| [Local Media + Retention](architecture_plans/LOCAL_MEDIA_RETENTION.md) | Architecture | Ingest downloads media → `resources/`, notes carry `local:`, retention trashes orphaned sources (stages 1–4 + 2b/2c DONE; deep page-video pending) |
| [Agent Escalation](architecture_plans/AGENT_ESCALATION.md) | Architecture | Stateful browser-agent debugs extraction failures (session+fix-log+WS bridge+extension tools+activity UI BUILT, flag `AGENT_ESCALATION_ENABLED`; host-wrapper multi-turn + richer UI pending) |
| [Extension Media Capture](architecture_plans/EXTENSION_MEDIA_CAPTURE_ARCH.md) | Architecture | "What's on this page?" DOM scanner + candidate picker [NOT IMPLEMENTED] |
| [Sync Retention Plan](architecture_plans/SYNC_RETENTION_PLAN.md) | Architecture | Drive delete propagation, orphan janitor, quota visibility [NOT IMPLEMENTED] |
| [PWA Mobile Architecture](architecture_plans/PWA_MOBILE_ARCH.md) | Architecture | Installable offline PWA; P1–P4 built but dormant in `frontend/src/pwa/` |
| [PWA FLOWS](frontend/src/pwa/FLOWS.md) | Flows | Mobile shell, offline review seam, share-target, activation steps |
| [Capture FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/capture/FLOWS.md) | Flows | Resource intake → durable `capture` queue → ingest → notes |
| [Tracks FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/tracks/FLOWS.md) | Flows | Learning tracks: CRUD, Today planner (capacity/deadline/MoSCoW), Progress, FSRS handoff |
| [Feed FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/feed/FLOWS.md) | Flows | Passive Feed backend: sources/feed_items, poll worker, accept/bin/restore (P1a/P2/P3, implemented) |
| [Passive Feed plan](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/feed/FLOWS_feed.md) | Flows (plan) | Subscriptions → swipe Feed → Learn; P0–P3 shipped (offline swipe, subscribe-any-URL, text posts via capture queue) |
| [PWA backend FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/pwa/FLOWS.md) | Flows | Drive-mediated offline: encrypted `_offline/` export, `_mailbox/` consume |
| [Notes FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/notes/FLOWS.md) | Flows | Note index, startup disk sync, file CRUD |
| [Settings FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/settings/FLOWS.md) | Flows | `app_settings` EAV table, settings API |
| [Config FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/config/FLOWS.md) | Flows | Spring Security auth, remember-me, app config |
| [Chrono FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/chrono/FLOWS.md) | Flows | Daily jobs trigger points (FileMover, bankruptcy, regen) |
| [Common FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/common/FLOWS.md) | Flows | Shared queue/concurrency infra (WorkerLane etc.) |
| [Media FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/media/FLOWS.md) | Flows | Upload + media serving |
| [Host FS FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/hostfs/FLOWS.md) | Flows | Host filesystem browser / vault picker |
| [Workspace FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/workspace/FLOWS.md) | Flows | `_workspace/` manual media shelf (legacy Learn library) |
| [Frontend API FLOWS](frontend/src/api/FLOWS.md) | Flows | API client layer, ApiError |
| [Frontend Pages FLOWS](frontend/src/pages/FLOWS.md) | Flows | MainPage, Learn (= Inbox review), Review, Dashboard, Settings |
| [Frontend Components FLOWS](frontend/src/components/FLOWS.md) | Flows | Components overview (InboxReview, LearnLayout, SplitLayout, ...); points to editor/FLOWS.md |
| [Frontend Editor FLOWS](frontend/src/editor/FLOWS.md) | Flows | CM6 note editor: live preview, wiki-links, embeds/attachments, math/tables, save path |
| [Frontend Store FLOWS](frontend/src/store/FLOWS.md) | Flows | Zustand state shape + actions |
| [Frontend Utils FLOWS](frontend/src/utils/FLOWS.md) | Flows | diff/patch, search, misc helpers |
| [Extension FLOWS](extension/FLOWS.md) | Flows | Chrome extension capture into the Learn queue (incl. Subscribe button) |
| [Firefox Extension FLOWS](extension-firefox/FLOWS.md) | Flows | Firefox build of the capture extension |
| [Backend Testing](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/FLOWS.md#testing) | Testing | Unit tests (7 service layers), MockMvc controller tests, Testcontainers IT |
| [Frontend Testing](frontend/FLOWS.md#testing) | Testing | Vitest: useStore, TabBar, FolderTree filter, diff, LoginModal, NewNoteForm |
| [CI Pipeline](.github/workflows/ci.yml) | CI/CD | GitHub Actions: backend-unit, backend-integration, frontend, docker-build |
| [Deploy/Boot FLOWS](linux_scripts/FLOWS.md) | Flows | systemd boot service, restart-on-crash, log querying, host-wrapper respawn |
| [Desktop Shell FLOWS](desktop/FLOWS.md) | Flows | Electron quit-breaker: loads live site, window-close intercept → anti-quitter quote, Windows build via dockerized wine |
