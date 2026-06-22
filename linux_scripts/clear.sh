#!/usr/bin/env bash
#
# Clean-slate reset for ObsidianOptimizer. Wipes this project's database volume so
# the next start rebuilds EVERYTHING from the vault — full re-index, re-embed,
# re-generate cards.
#
# Removes ONLY this project's volumes:
#   <project>_postgres_data    notes index, note_chunks, cards, reviews, sync_queue,
#                              app_settings, pending_image_jobs — all app state.
#
# KEEPS <project>_embedder_models by default (the ~3-4GB ONNX/CLIP/whisper cache)
# so the next boot is fast. Pass --include-models to wipe that too.
#
# NEVER touches your vault (host bind-mount) or other projects' volumes.
#
# Usage:
#   ./clear.sh                   # wipe DB, keep model cache (fast reboot)
#   ./clear.sh --include-models  # wipe DB + models (absolute zero)
#   ./clear.sh --force           # no confirmation prompt

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE="$ROOT_DIR/docker-compose.yml"

if [[ ! -f "$COMPOSE" ]]; then
    echo "Error: docker-compose.yml not found at $COMPOSE" >&2
    exit 1
fi

INCLUDE_MODELS=false
FORCE=false
for arg in "$@"; do
    case "$arg" in
        --include-models) INCLUDE_MODELS=true ;;
        --force)          FORCE=true ;;
    esac
done

# Project name docker compose derives from the directory (lowercased, sanitised).
PROJ=$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')

echo ""
echo "=== CLEAN SLATE: $PROJ ==="

TARGETS=("${PROJ}_postgres_data")
if [[ "$INCLUDE_MODELS" == true ]]; then
    TARGETS+=("${PROJ}_embedder_models")
fi

echo "Will remove:"
for t in "${TARGETS[@]}"; do echo "  - $t"; done
if [[ "$INCLUDE_MODELS" == false ]]; then
    echo "  (keeping ${PROJ}_embedder_models — pass --include-models to wipe it too)"
fi
echo "  Your vault is a host folder and is NOT touched."

if [[ "$FORCE" == false ]]; then
    read -r -p $'\nThis ERASES the database (notes index, cards, reviews, sync queue, settings). Type \'yes\' to proceed: ' ans
    if [[ "$ans" != "yes" ]]; then
        echo "Aborted — nothing removed."
        exit 0
    fi
fi

echo ""
echo "Stopping containers..."
# Include tunnel profile so cloudflared is torn down too if it was running.
docker compose -f "$COMPOSE" --profile tunnel down

for v in "${TARGETS[@]}"; do
    if docker volume ls -q -f "name=$v" | grep -qx "$v"; then
        docker volume rm "$v" && echo "  removed $v"
    else
        echo "  (not found: $v — already clean)"
    fi
done

echo ""
echo "=== DONE ==="
echo "Next launch boots from a clean slate:"
echo "  ./linux_scripts/start.sh   (or  docker compose up --build)"
echo "  -> fresh DB -> full re-index from vault -> re-embed -> card generation"
if [[ "$INCLUDE_MODELS" == false ]]; then
    echo "  -> models reused from cache (fast boot)"
fi
