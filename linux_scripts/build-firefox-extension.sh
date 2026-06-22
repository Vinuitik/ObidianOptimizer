#!/usr/bin/env bash
# Generates extension-firefox/ — a Firefox-loadable copy of extension/.
#
# Why a copy: Chrome MV3 requires `background.service_worker`; Firefox MV3 wants
# `background.scripts`. They can't share one manifest.json, and Firefox's
# "Load Temporary Add-on" always reads the directory's manifest.json. So we copy
# the source and drop the Firefox manifest in as manifest.json. The JS is shared
# verbatim (it uses the `browser ?? chrome` shim, so it runs in both engines).
#
# Run after any edit to extension/, then in Firefox:
#   about:debugging#/runtime/this-firefox -> Load Temporary Add-on -> pick
#   extension-firefox/manifest.json
#
# Chrome/Edge/Brave keep loading the original extension/ folder unchanged.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

SRC="$ROOT_DIR/extension"
OUT="$ROOT_DIR/extension-firefox"

rm -rf "$OUT"
mkdir -p "$OUT"

cp -r "$SRC/." "$OUT/"
rm -f "$OUT/manifest.firefox.json"
cp "$SRC/manifest.firefox.json" "$OUT/manifest.json"

echo "Firefox extension ready: $OUT"
echo "Load it: about:debugging#/runtime/this-firefox -> Load Temporary Add-on -> select extension-firefox/manifest.json"
