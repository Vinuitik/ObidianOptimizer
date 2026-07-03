# Architecture Plans — Status Index

Navigation only; each plan carries its own detail. Implemented systems are documented in
per-subsystem FLOWS.md files next to the code — a plan marked IMPLEMENTED is kept only as
design rationale. Superseded/implemented plans get deleted (git history keeps them):
so far LINK_SNIFFER_AGENT, VIDEO_MANAGER, MOBILE (native), INFO_DASHBOARD, DOMAIN_SETUP,
ENCRYPTION (2026-07-02 cleanup).

| Plan | Status | One-liner |
|---|---|---|
| [INGESTION_V2_FLOWS](INGESTION_V2_FLOWS.md) | ✗ design, in progress | block-level anchored ingestion; sub-page locators, deterministic segmentation, retention; text-native done, A/V TODO |
| [INGEST_AGENT_ARCH](INGEST_AGENT_ARCH.md) | ✅ implemented (v1) | resource → notes pipeline; kept as rationale (live doc: embedder/ingest/FLOWS.md); superseded in part by INGESTION_V2 |
| [CAPTURE_ARCH](CAPTURE_ARCH.md) | ◐ phases 1–2 shipped | capture model, ordered proposed notes, Learn queue — see its STATUS table |
| [PWA_MOBILE_ARCH](PWA_MOBILE_ARCH.md) | ◐ built, dormant | PWA P1–P4 code-complete in frontend/src/pwa/, not activated; §15–16 new scope |
| [EXTENSION_MEDIA_CAPTURE_ARCH](EXTENSION_MEDIA_CAPTURE_ARCH.md) | ✗ planned | find videos/URIs on the current page → picker → existing capture/download |
| [SYNC_RETENTION_PLAN](SYNC_RETENTION_PLAN.md) | ✗ planned | Drive delete propagation + orphan janitor + quota visibility |
| [FLASHCARDS_ARCH](FLASHCARDS_ARCH.md) | ◐ partial | generation shipped; FSRS/bandit/assignments half open |
| [FSRS_REWORK_PLAN](FSRS_REWORK_PLAN.md) | ⏸ awaiting permit | decisions locked, nothing implemented |
| [PIPELINE_HARDENING_PLAN](PIPELINE_HARDENING_PLAN.md) | ◐ phases 1–2a done | gates + per-note batching landed; rest open |
| [ML_ARCH](ML_ARCH.md) | ✅ largely implemented | MCP server, pgvector search, image pipeline (live doc: ml/FLOWS.md) |
| [EXTENSION_ARCH](EXTENSION_ARCH.md) | ✗ packaging only | trimmed 2026-07-02; the React plan is dead, vanilla extension shipped |
| [ONBOARDING_ARCH](ONBOARDING_ARCH.md) | ✗ planned | in-app glow tour |
| [OPTIMIZATION_ARCH](OPTIMIZATION_ARCH.md) | ✗ parked | apply per measured bottleneck; mobile section superseded by PWA |
| [DEPLOYMENT_ARCH](DEPLOYMENT_ARCH.md) | ✗ planned, stale in parts | ship-to-friends installer; rewrite against current compose first |
| [KAGGLE_AGENT_ARCH](KAGGLE_AGENT_ARCH.md) | ✗ separate project | future `KaggleOptimizer` repo |
