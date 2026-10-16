# Test Net Expansion Plan (2026-10-16)

Goal: catch "new feature broke an old one" before it goes live. Three workstreams, Sonnet agents in worktrees, Opus merges.

## Gate (target end state)
`git push` → `.git/hooks/pre-push` (thin exec of versioned `linux_scripts/pre-push.sh`)
→ offline suites (`test.sh`) → FAIL = abort
→ `systemctl stop obsidian-optimizer` (free RAM; box has 7 GB)
→ test stack: `docker compose -p obsopt-test -f docker-compose.yml -f docker-compose.test.yml up --build -d` (fresh volumes, no tunnel)
→ wait healthy → `e2e` Playwright smoke (non-LLM flows) → `down -v`
→ PASS + master pushed → `systemctl start` (builds + deploys new code)
→ FAIL → restore OLD live version without rebuilding (flag `.skip-build-once` consumed by start.sh; live images `obidianoptimizer-*` untouched because test stack uses project `obsopt-test`) → abort push
- `SKIP_E2E=1 git push` skips only the e2e stage.

## Workstreams
- **A — Regression**: every bug-fix commit since 2026-08-01 lacking a test that would catch it → add one (Java/Vitest/pytest, whichever layer the bug lived in).
- **B — Gaps**: `config` (security/login), `sync` (2.5k LOC / 7 tests), `workspace`, `stats`, `hostfs`; embedder `job_registry`, `agent_ws`. Prefer fast unit tests; ITs only where the DB is the point.
- **C — E2E + gate**: `docker-compose.test.yml`, `e2e/` Python Playwright smoke, `e2e` suite in `test.sh`, `linux_scripts/pre-push.sh`, `start.sh` skip-build-once, FLOWS.

## Rules for all
- Tests that reveal a REAL bug: mark skipped/xfail with reason `KNOWN BUG: ...`, list it in the report. Do NOT fix production code.
- Every test names the break it catches (superpowers writing-good-tests): hand-derived literal expectations, no mirror assertions, mock only external/slow deps.
- Keep pre-push fast: unit over IT.
