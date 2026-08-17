#!/usr/bin/env bash
# Zips extension/ (Chrome-ready as-is — manifest.json needs no merge) into
# ext-dist/obsidian-optimizer-chrome.zip for the /get-app "Get extension" download.
#
# Chrome has no self-hosted-install path like Firefox's signed .xpi (see
# extension/FLOWS.md) — it refuses to install anything from outside the Chrome Web
# Store. So this is just a convenience zip for manual "Load unpacked", not an
# auto-installable package. No signing, no version bump; re-run after any edit to
# extension/ that should reach the download link.
#
# Served by nginx's existing /ext/ alias (frontend/nginx.conf.template) — same
# route Firefox's .xpi/updates.json already use.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

SRC="$ROOT_DIR/extension"
DIST="$ROOT_DIR/ext-dist"
NAME="obsidian-optimizer-chrome"

command -v zip >/dev/null || { echo "✖ zip is required (sudo apt install zip)"; exit 1; }

mkdir -p "$DIST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

OUT_DIR="$TMP/$NAME"
mkdir -p "$OUT_DIR"
cp -r "$SRC/." "$OUT_DIR/"
rm -f "$OUT_DIR/manifest.firefox.overlay.json" "$OUT_DIR/FLOWS.md"

rm -f "$DIST/$NAME.zip"
( cd "$TMP" && zip -rq "$DIST/$NAME.zip" "$NAME" )

echo "▶ $DIST/$NAME.zip"
