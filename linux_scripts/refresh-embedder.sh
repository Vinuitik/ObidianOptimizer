#!/usr/bin/env bash
# Nightly/boot refresh of the embedder image so yt-dlp (and any other unpinned dep) actually
# picks up new releases. `docker compose build` alone does NOT do this on its own schedule:
# Docker's layer cache keys off requirements.txt's *content*, so an unchanged file replays the
# cached `pip install` layer forever even though most version constraints are unbounded
# (yt-dlp>=X). --no-cache forces a real re-resolve.
#
# Why this matters here specifically: yt-dlp ships near-daily fixes for sites that change their
# anti-scraping behavior (Instagram, TikTok, etc). A stale yt-dlp is a silent ingest-failure
# source, not just a "missing feature."
#
# Why this has a rollback: a --no-cache rebuild re-resolves EVERY unpinned dependency, not just
# yt-dlp — the first real run of this script pulled in a breaking mcp 2.x that crash-looped the
# embedder (FastMCP renamed to MCPServer). An unattended nightly job that can silently leave prod
# crash-looping overnight is worse than the staleness it's meant to fix, so: tag the current
# working image before touching anything, and if the rebuilt container doesn't reach `healthy`
# within its own healthcheck budget, revert to the tagged image and fail loudly instead of
# leaving a broken container running.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

IMAGE="obidianoptimizer-embedder"
ROLLBACK_TAG="${IMAGE}:pre-refresh"
CONTAINER="obidianoptimizer-embedder-1"

# docker-compose.yml: start_period 600s + interval 10s * retries 12 = up to ~720s before the
# healthcheck itself gives up and marks the container unhealthy. Give it a bit of headroom.
HEALTH_TIMEOUT_S=780
HEALTH_POLL_S=10

wait_for_health() {
    local waited=0
    while (( waited < HEALTH_TIMEOUT_S )); do
        local status
        status="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")"
        case "$status" in
            healthy) return 0 ;;
            unhealthy) return 1 ;;
            missing) return 1 ;;
        esac
        sleep "$HEALTH_POLL_S"
        waited=$(( waited + HEALTH_POLL_S ))
    done
    return 1   # timed out still starting/unhealthy
}

if docker image inspect "$IMAGE:latest" >/dev/null 2>&1; then
    docker tag "$IMAGE:latest" "$ROLLBACK_TAG"
    echo "[refresh-embedder] $(date -Is) tagged current image as $ROLLBACK_TAG for rollback"
fi

echo "[refresh-embedder] $(date -Is) building embedder (--no-cache) ..."
if ! docker compose build --no-cache embedder; then
    echo "[refresh-embedder] build FAILED — leaving the currently running embedder untouched" >&2
    exit 1
fi

echo "[refresh-embedder] build ok — recreating container"
docker compose up -d embedder

echo "[refresh-embedder] waiting for the rebuilt embedder to report healthy (up to ${HEALTH_TIMEOUT_S}s) ..."
if wait_for_health; then
    docker compose exec -T embedder yt-dlp --version | xargs -I{} echo "[refresh-embedder] healthy — yt-dlp now at {}"
    docker image rm "$ROLLBACK_TAG" >/dev/null 2>&1 || true
    exit 0
fi

echo "[refresh-embedder] rebuilt embedder did NOT become healthy — rolling back to the pre-refresh image" >&2
if docker image inspect "$ROLLBACK_TAG" >/dev/null 2>&1; then
    docker tag "$ROLLBACK_TAG" "$IMAGE:latest"
    docker compose up -d embedder
    echo "[refresh-embedder] rolled back and recreated the container from the pre-refresh image" >&2
else
    echo "[refresh-embedder] no rollback image available — embedder is left as-is, needs manual attention" >&2
fi
exit 1
