#!/usr/bin/env bash
# install-service.sh - install & enable the obsidian-optimizer systemd service so
# start.sh runs automatically on boot and stays online. Idempotent; re-run to update.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_SRC="$SCRIPT_DIR/obsidian-optimizer.service"
UNIT_DST="/etc/systemd/system/obsidian-optimizer.service"

if [[ $EUID -ne 0 ]]; then
    echo "[install] re-running with sudo ..."
    exec sudo bash "$0" "$@"
fi

echo "[install] copying unit -> $UNIT_DST"
install -m 0644 "$UNIT_SRC" "$UNIT_DST"

echo "[install] reloading systemd"
systemctl daemon-reload

echo "[install] enabling + starting service"
systemctl enable --now obsidian-optimizer.service

echo "[install] done. Useful commands:"
echo "  systemctl status obsidian-optimizer     # health"
echo "  journalctl -u obsidian-optimizer -f     # live logs (build + compose)"
echo "  systemctl restart obsidian-optimizer    # redeploy"
echo "  systemctl stop obsidian-optimizer       # take offline"
