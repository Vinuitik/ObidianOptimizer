#!/usr/bin/env bash
# Prune Docker build cache, stopped containers, dangling images, and unused networks.
# On native Linux there is no virtual disk to compact — freed space returns to the
# filesystem immediately (unlike WSL2's ext4.vhdx which needs manual compaction).
#
# Usage:
#   ./docker-prune.sh               # prune build cache, stopped containers, dangling images, unused networks
#   ./docker-prune.sh --deep-images # also remove ALL images not used by a running container

set -euo pipefail

DEEP_IMAGES=false
for arg in "$@"; do
    case "$arg" in
        --deep-images) DEEP_IMAGES=true ;;
    esac
done

echo ""
echo "=== DOCKER CLEANUP (volumes are NOT touched) ==="
echo "  usage before:"
docker system df

echo "  stopped containers..."
docker container prune -f

echo "  build cache..."
docker builder prune -f

if [[ "$DEEP_IMAGES" == true ]]; then
    echo "  ALL unused images (--deep-images)..."
    docker image prune -a -f
else
    echo "  dangling images..."
    docker image prune -f
fi

echo "  unused networks..."
docker network prune -f

echo ""
echo "  usage after:"
docker system df

if command -v nvidia-smi &>/dev/null; then
    echo ""
    echo "GPU memory right now:"
    nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader
    echo "(VRAM is freed by stopping the process that holds it — i.e. restart"
    echo " the embedder container. There is no safe global 'flush VRAM' on a"
    echo " consumer GPU, so this script only reports it.)"
fi

echo ""
echo "=== DONE ==="
