#!/usr/bin/env bash
# Stop the whole stack and keep it down (across systemd restarts AND reboots)
# until linux_scripts/start.sh is run by hand. See the STOP_FLAG block in start.sh.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
touch "$ROOT_DIR/.stopped"   # flag first, so a racing systemd restart sees it

if systemctl is-active --quiet obsidian-optimizer 2>/dev/null; then
    # start.sh's EXIT trap runs `compose down` and kills the host-wrapper.
    sudo -n systemctl stop obsidian-optimizer
else
    # Not under systemd (start.sh run by hand): tear down directly.
    docker compose -f "$ROOT_DIR/docker-compose.yml" --profile tunnel down --remove-orphans
    port=$(grep -E '^\s*PORT\s*=' "$ROOT_DIR/.env" | head -1 | sed 's/^\s*PORT\s*=\s*//' | tr -d "\"'")
    pids=$(lsof -ti :"${port:-5001}" 2>/dev/null || true)
    [[ -n "$pids" ]] && kill $pids 2>/dev/null || true
fi
echo "Stopped. It stays down until you run linux_scripts/start.sh."
