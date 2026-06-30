#!/usr/bin/env bash
# redeploy.sh - restart the full Obsidian Optimizer stack on the server.
#
# Called by publish.ps1 (after git pull). Stops any running detached start.sh,
# tears the stack down, then relaunches start.sh DETACHED so it keeps running
# (tunnel + host-wrapper + compose) after this SSH session ends.
set -euo pipefail
cd "$HOME/Desktop/ObidianOptimizer"

echo "[redeploy] stopping existing start.sh / stack ..."
pkill -f "linux_scripts/start.sh" 2>/dev/null || true
sleep 1
docker compose --profile tunnel down --remove-orphans 2>/dev/null || true
fuser -k 5500/tcp 2>/dev/null || true
pkill -f "host-wrapper/.venv/bin/python" 2>/dev/null || true

echo "[redeploy] relaunching start.sh (detached) ..."
setsid nohup bash linux_scripts/start.sh > "$HOME/obsidian_start.log" 2>&1 < /dev/null &
echo "[redeploy] done - logs at ~/obsidian_start.log"
