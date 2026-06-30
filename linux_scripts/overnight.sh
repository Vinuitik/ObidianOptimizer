#!/usr/bin/env bash
# overnight.sh — GUARD an already-running stack overnight. Does NOT start, stop,
# rebuild, or restart anything on launch. Start your stack yourself first
# (e.g. ./linux_scripts/start.sh), confirm it's up, THEN run this to babysit it.
#
# Two jobs:
#   1. Keep the laptop awake with the lid CLOSED (no suspend / idle / lid-switch)
#      for as long as the guard runs — via a systemd-inhibit lock. Nothing is
#      changed permanently; quit the guard and normal power behaviour returns.
#   2. Thermal kill-switch: poll CPU + GPU temps. If either stays over its limit
#      for BREACH_STREAK consecutive reads, run an ORDERLY stop (graceful
#      `docker compose down`), not a hard kill.
#
# Ctrl+C = stop guarding only. It releases the inhibitor and exits but LEAVES
# your stack running. Only a sustained thermal breach actually stops the stack.
#
# Usage:
#   linux_scripts/overnight.sh
#   CPU_LIMIT=85 GPU_LIMIT=80 linux_scripts/overnight.sh
#   tail -f linux_scripts/logs/overnight-*.log     # watch from another terminal
#
# Portability (for your other projects): override the two project-specific bits
#   STOP_CMD   command run on thermal breach to stop the stack gracefully
#   ALIVE_CMD  command (exit 0 = still running) used to detect the stack vanished
# e.g.  STOP_CMD="pkill -TERM -f myserver" ALIVE_CMD="pgrep -f myserver" overnight.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"

# ---- Tunables (override via env) ----
CPU_LIMIT="${CPU_LIMIT:-90}"        # °C — Comet Lake-H Tjmax ~100; 90 leaves headroom
GPU_LIMIT="${GPU_LIMIT:-85}"        # °C — GTX 1650 throttles ~83-87; 85 is a safe cap
POLL_SECS="${POLL_SECS:-15}"        # seconds between temperature reads
BREACH_STREAK="${BREACH_STREAK:-3}" # consecutive over-limit reads before stopping
                                    #   (3 x 15s = ~45s sustained, ignores brief spikes)

# Project-specific hooks (defaults target this project's docker-compose stack).
STOP_CMD="${STOP_CMD:-docker compose -f \"$COMPOSE_FILE\" down --timeout 30}"
ALIVE_CMD="${ALIVE_CMD:-}"          # empty -> auto: docker compose ps for this project

# Re-exec the whole guard under an inhibitor lock so the lid stays "open" for the
# entire run. The lock is released automatically the moment this process exits.
if [[ -z "${_OVERNIGHT_INHIBITED:-}" ]]; then
    exec systemd-inhibit \
        --what=sleep:idle:handle-lid-switch \
        --who="ObsidianOptimizer overnight guard" \
        --why="overnight processing run" \
        --mode=block \
        env _OVERNIGHT_INHIBITED=1 "$0" "$@"
fi

LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"
RUN_LOG="$LOG_DIR/overnight-$(date +%Y%m%d-%H%M%S).log"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$RUN_LOG"; }

# CPU temp source: prefer x86_pkg_temp (package temp), fall back to acpitz.
CPU_TEMP_FILE=""
for z in /sys/class/thermal/thermal_zone*; do
    [[ "$(cat "$z/type" 2>/dev/null)" == "x86_pkg_temp" ]] && { CPU_TEMP_FILE="$z/temp"; break; }
done
if [[ -z "$CPU_TEMP_FILE" ]]; then
    for z in /sys/class/thermal/thermal_zone*; do
        [[ "$(cat "$z/type" 2>/dev/null)" == "acpitz" ]] && { CPU_TEMP_FILE="$z/temp"; break; }
    done
fi
cpu_temp() { [[ -r "$CPU_TEMP_FILE" ]] && awk '{printf "%d", $1/1000}' "$CPU_TEMP_FILE" || echo ""; }
gpu_temp() { nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d ' '; }

# Is the guarded stack still up? Used to auto-exit if you stop it yourself.
stack_alive() {
    if [[ -n "$ALIVE_CMD" ]]; then
        bash -c "$ALIVE_CMD" >/dev/null 2>&1
    elif [[ -f "$COMPOSE_FILE" ]] && command -v docker >/dev/null 2>&1; then
        [[ -n "$(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)" ]]
    else
        return 0   # can't tell -> assume alive; run until breach or Ctrl+C
    fi
}

DID_STOP=0
orderly_stop() {
    [[ "$DID_STOP" == 1 ]] && return
    DID_STOP=1
    log "ORDERLY STOP — $1"
    bash -c "$STOP_CMD" >>"$RUN_LOG" 2>&1 || true
    log "Stop command finished. Inhibitor released on exit."
}

# Ctrl+C / TERM: stop GUARDING only — leave the stack running.
trap 'log "Interrupted — releasing inhibitor, leaving the stack running."; exit 130' INT TERM

log "Overnight guard starting (does NOT relaunch your stack)."
log "CPU temp source: ${CPU_TEMP_FILE:-<none found>}"
log "Limits: CPU>=${CPU_LIMIT}C  GPU>=${GPU_LIMIT}C  |  poll=${POLL_SECS}s  streak=${BREACH_STREAK}"
log "Lid can be closed — sleep/idle/lid-switch inhibited for the duration."
log "Log: $RUN_LOG"

if ! stack_alive; then
    log "No running stack detected — nothing to guard. Start it first, then run this. Exiting."
    exit 1
fi

streak=0
while stack_alive; do
    c="$(cpu_temp)"; g="$(gpu_temp)"
    over=0
    [[ -n "$c" && "$c" -ge "$CPU_LIMIT" ]] && over=1
    [[ -n "$g" && "$g" -ge "$GPU_LIMIT" ]] && over=1
    if [[ "$over" == 1 ]]; then
        streak=$((streak + 1))
        log "TEMP cpu=${c:-NA}C gpu=${g:-NA}C  >> OVER LIMIT ($streak/$BREACH_STREAK)"
        if [[ "$streak" -ge "$BREACH_STREAK" ]]; then
            orderly_stop "thermal limit sustained — cpu=${c:-NA}C gpu=${g:-NA}C"
            break
        fi
    else
        [[ "$streak" -gt 0 ]] && log "TEMP cpu=${c:-NA}C gpu=${g:-NA}C  (recovered)"
        streak=0
    fi
    sleep "$POLL_SECS"
done

[[ "$DID_STOP" == 0 ]] && log "Guarded stack is no longer running — releasing inhibitor, exiting."
log "Overnight guard done."
