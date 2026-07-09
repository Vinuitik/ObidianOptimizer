#!/usr/bin/env bash
# Unified test runner — every suite in one command, every output stored.
#
#   ./linux_scripts/test.sh                 # all offline suites
#   ./linux_scripts/test.sh embedder java-unit
#   ./linux_scripts/test.sh live            # opt-in LIVE suites (drive-live, ingest-live)
#
# Suites:
#   host-wrapper  pytest in host-wrapper/.venv (pytest auto-installed once)
#   embedder      pytest inside the obidianoptimizer-embedder image (real deps),
#                 current source bind-mounted over /app
#   java-unit     ./mvnw test -Dtest='!*IT'   (no Docker needed)
#   java-it       ./mvnw test -Dtest='*IT'    (Testcontainers → Docker)
#   frontend      vitest inside node:20-alpine, frontend/ bind-mounted
#   drive-live    DriveLiveIT — REAL Google Drive (creds via env; see Suite A docs)
#   ingest-live   live ingestion E2E against the running stack (fast.ai lesson 4)
#
# Outputs: test-results/<timestamp>/<suite>.log + summary.txt; symlink test-results/latest.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/test-results/$TS"
mkdir -p "$OUT"
ln -sfn "$OUT" "$ROOT/test-results/latest"

OFFLINE_SUITES=(host-wrapper embedder java-unit java-it frontend)
LIVE_SUITES=(drive-live ingest-live)

declare -A RESULT DURATION

run_suite() {   # $1 = suite name, rest = command; runs in current dir
  local name="$1"; shift
  local log="$OUT/$name.log"
  local start=$SECONDS
  echo "── $name ─────────────────────────────────────────────"
  echo "\$ $*" | tee "$log"
  "$@" >>"$log" 2>&1
  local rc=$?
  DURATION[$name]=$((SECONDS - start))
  if [ $rc -eq 0 ]; then RESULT[$name]=PASS; else RESULT[$name]="FAIL($rc)"; fi
  # last lines inline so the console isn't silent, full detail stays in the log
  tail -n 12 "$log" | sed 's/^/    /'
  echo "    → ${RESULT[$name]} in ${DURATION[$name]}s — full log: test-results/$TS/$name.log"
}

suite_host_wrapper() {
  local py="$ROOT/host-wrapper/.venv/bin/python"
  [ -x "$py" ] || { echo "host-wrapper/.venv missing"; RESULT[host-wrapper]=SKIP; return; }
  "$py" -m pytest --version >/dev/null 2>&1 || "$py" -m pip install -q pytest
  cd "$ROOT/host-wrapper" && run_suite host-wrapper "$py" -m pytest tests/ -q --tb=short
}

suite_embedder() {
  docker image inspect obidianoptimizer-embedder >/dev/null 2>&1 \
    || { echo "embedder image missing — build the stack first"; RESULT[embedder]=SKIP; return; }
  cd "$ROOT" && run_suite embedder docker run --rm \
    -v "$ROOT/embedder:/app" -w /app \
    -e MCP_API_TOKEN=test-token -e DATABASE_URL= -e VAULT_DIR=/tmp/vault \
    --entrypoint python3.11 obidianoptimizer-embedder -m pytest tests/ -q --tb=short
}

suite_java_unit() {
  cd "$ROOT/obsidian_optimizer/obsidian" \
    && run_suite java-unit ./mvnw -q test -Dtest='!*IT' -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress
}

suite_java_it() {
  docker info >/dev/null 2>&1 || { echo "Docker unavailable — ITs need Testcontainers"; RESULT[java-it]=SKIP; return; }
  cd "$ROOT/obsidian_optimizer/obsidian" \
    && run_suite java-it ./mvnw -q test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress
}

suite_frontend() {
  [ -d "$ROOT/frontend/node_modules" ] || { echo "frontend/node_modules missing — run npm ci (via docker) first"; RESULT[frontend]=SKIP; return; }
  cd "$ROOT" && run_suite frontend docker run --rm \
    -v "$ROOT/frontend:/app" -w /app node:20-alpine npx vitest run
}

suite_drive_live() {
  # LIVE Google Drive round-trip (DriveLiveIT). Creds are pulled from the running
  # stack's app_settings (same account; the IT writes only inside a throwaway
  # ObsidianOptimizer-LIVETEST-<ts> folder and hard-deletes it afterwards).
  local q="docker exec obidianoptimizer-postgres-1 psql -U obsidian -d obsidian -tA -c"
  local cid sec tok pass
  cid=$($q "SELECT value FROM app_settings WHERE key='syncClientId'" 2>/dev/null)
  sec=$($q "SELECT value FROM app_settings WHERE key='syncClientSecret'" 2>/dev/null)
  tok=$($q "SELECT value FROM app_settings WHERE key='sync.refresh_token'" 2>/dev/null)
  pass=$($q "SELECT value FROM app_settings WHERE key='syncPassphrase'" 2>/dev/null)
  if [ -z "$cid" ] || [ -z "$sec" ] || [ -z "$tok" ] || [ -z "$pass" ]; then
    echo "drive-live: could not read Drive creds from the running stack's app_settings"
    RESULT[drive-live]=SKIP; return
  fi
  export DRIVE_LIVE=1 DRIVE_LIVE_CLIENT_ID="$cid" DRIVE_LIVE_CLIENT_SECRET="$sec" \
         DRIVE_LIVE_REFRESH_TOKEN="$tok" DRIVE_LIVE_PASSPHRASE="$pass"
  cd "$ROOT/obsidian_optimizer/obsidian" \
    && run_suite drive-live ./mvnw -q test -Dtest='DriveLiveIT' --no-transfer-progress
  unset DRIVE_LIVE DRIVE_LIVE_CLIENT_ID DRIVE_LIVE_CLIENT_SECRET \
        DRIVE_LIVE_REFRESH_TOKEN DRIVE_LIVE_PASSPHRASE
}

suite_ingest_live() {
  local py="$ROOT/host-wrapper/.venv/bin/python"   # reuse: has requests/httpx
  cd "$ROOT/linux_scripts" && run_suite ingest-live "$py" ingest_live_e2e.py
}

main() {
  local wanted=("$@")
  [ ${#wanted[@]} -eq 0 ] && wanted=("${OFFLINE_SUITES[@]}")
  [ "${wanted[0]:-}" = "live" ] && wanted=("${LIVE_SUITES[@]}")
  [ "${wanted[0]:-}" = "all" ] && wanted=("${OFFLINE_SUITES[@]}")

  for s in "${wanted[@]}"; do
    case "$s" in
      host-wrapper) suite_host_wrapper ;;
      embedder)     suite_embedder ;;
      java-unit)    suite_java_unit ;;
      java-it)      suite_java_it ;;
      frontend)     suite_frontend ;;
      drive-live)   suite_drive_live ;;
      ingest-live)  suite_ingest_live ;;
      *) echo "unknown suite: $s (known: ${OFFLINE_SUITES[*]} ${LIVE_SUITES[*]})" ;;
    esac
  done

  echo
  echo "══ SUMMARY ($TS) ══════════════════════════════════════" | tee "$OUT/summary.txt"
  local rc=0
  for s in "${wanted[@]}"; do
    printf "  %-14s %-10s %ss\n" "$s" "${RESULT[$s]:-N/A}" "${DURATION[$s]:-—}" | tee -a "$OUT/summary.txt"
    [[ "${RESULT[$s]:-}" == FAIL* ]] && rc=1
  done
  echo "  logs: test-results/$TS/" | tee -a "$OUT/summary.txt"
  exit $rc
}

main "$@"
